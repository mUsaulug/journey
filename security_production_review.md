# 🔍 GITHUB CODE REVIEW RAPORU (Security & Production Readiness)

## ADIM 1 — Repository Taraması

### Java dosyaları
- 23 adet `.java` dosyası bulundu.
- Kritik odak dosyaları mevcut: `EventConsumer`, `CardApplicationOrchestrator`, `RedisStateStore`, `KafkaActionPublisher`, `PostgresEventStore`.

### Config dosyaları
- `src/main/resources/application.yml`
- `docker-compose.yml`
- `init.sql`
- `pom.xml`

### Test kapsaması
- `src/test` klasörü yok (otomatik test bulunmuyor).

### README kalite değerlendirmesi
- Artılar: mimari diyagram, akış, çalıştırma adımları, örnek API çağrıları var.
- Eksiler: güvenlik sınırları (authn/authz), rate limiting, production hardening, SLO/monitoring, incident runbook içeriği yok.

## ADIM 2 — Kritik dosya inceleme sırası
1. ✅ `EventConsumer.java`
2. ✅ `CardApplicationOrchestrator.java`
3. ✅ `RedisStateStore.java`
4. ✅ `KafkaActionPublisher.java`
5. ✅ `PostgresEventStore.java`
6. ✅ `application.yml`
7. ✅ `init.sql`

---

## 🎯 Executive Summary
Bu servis functional olarak çalışabilir ama production güvenliği ve dayanıklılığı açısından ciddi boşluklar içeriyor. En kritik riskler: public test endpoint’lerinden yetkisiz event enjeksiyonu, action publish sürecinde “sessiz veri kaybı”, ve state update’te yarış koşulu nedeniyle journey bozulması. Performans tarafında ise senkron ve ölçülmeyen I/O zinciri, metriksizlik ve backpressure eksikliği throughput’u düşürüp incident tespitini geciktirir.

En kritik 3 finding:
1. **Auth olmayan test API ile Kafka’ya event basılabiliyor (P0).**
2. **`publishAction` hataları swallow edildiği için action kaybı görünmeden yaşanabilir (P0/P1).**
3. **Redis state update atomic değil, aynı customer için concurrent event’te lost update riski var (P1).**

---

## 🔐 Security Audit

### 🔴 Critical Issues (P0)

#### 1) Yetkisiz event enjeksiyonu (No AuthN/AuthZ)
**Dosya:** `TestController.java`  
**Satır:** ~30-100  
**Sorun:** `/api/test/events/{customerId}/{eventType}` endpoint’i public; herhangi bir auth kontrolü yok.  
**Risk:** Dış saldırgan üretimde sahte `APPROVAL/REJECTION` event’leri basıp müşteri journey’sini manipüle edebilir.  
**Çözüm:**
- `spring-boot-starter-security` + JWT/OAuth2 resource server zorunlu.
- `/api/test/**` endpoint’lerini sadece `dev` profile’da aç veya tamamen kaldır.
- WAF + IP allowlist + method-level authorization (`@PreAuthorize`) uygula.
**PoC:**
```bash
curl -X POST "http://localhost:8080/api/test/events/cust-victim/APPROVAL?segment=VIP"
```

#### 2) Aşırı detaylı DLQ mesajı ile sensitive/internal data sızıntısı
**Dosya:** `EventConsumer.java`  
**Satır:** ~130-170  
**Sorun:** DLQ payload içine `originalValue` (ham mesaj) + `stackTrace` + `errorMessage` yazılıyor.  
**Risk:** PII/secrets log-topics içinde uzun süre tutulabilir; internal sınıf/stack detayları saldırgana reconnaissance sağlar.  
**Çözüm:**
- DLQ’ya ham payload yerine masked/hashed alanlar koy.
- Stack trace’i sadece internal log’da tut; DLQ’da error code kullan.
- DLQ retention + encryption-at-rest + ACL zorunlu.
**PoC:**
```text
Attacker payload'a PII koyar -> parse fail tetikler -> PII aynen DLQ topic'ine düşer.
```

#### 3) Güvensiz varsayılan secret’lar (hard-coded fallback)
**Dosya:** `application.yml` + `docker-compose.yml`  
**Satır:** ~50-57 ve compose postgres env  
**Sorun:** Varsayılan `POSTGRES_USER=evam`, `POSTGRES_PASSWORD=evam_secret`; compose’da da plain text.  
**Risk:** Yanlış konfigürasyonda prod’a aynı credential taşınırsa kolay compromise.  
**Çözüm:**
- Secret manager (Vault/KMS/Secrets Manager).
- Plain-text fallback kaldır; startup’ta zorunlu env check.
- Rotation policy + least privilege DB user.
**PoC:**
```text
Misconfigured prod pod env default'a düşer -> bilinen credential ile DB erişimi alınır.
```

### 🟠 High Priority (P1)

#### 4) Broken access control: dashboard endpoint’i herkese açık
**Dosya:** `DashboardController.java`  
**Satır:** ~40-90  
**Sorun:** `/dashboard/stats` auth’suz; müşteri aksiyon mesajları ve dağılım datası döndürülüyor.  
**Risk:** İş metrikleri ve müşteri davranışları dışarı sızar.  
**Çözüm:** RBAC + response minimization + pagination + masking.
**PoC:**
```bash
curl http://localhost:8080/dashboard/stats
```

#### 5) Input validation eksik (customerId/eventType/segment)
**Dosya:** `TestController.java`, `EventConsumer.java`  
**Satır:** ~55-80, ~170-210  
**Sorun:** Boyut/format whitelist yok; user-controlled değerler log, Redis key ve message içine giriyor.  
**Risk:** Log injection, key-space abuse, büyük payload ile DoS.  
**Çözüm:** Bean Validation (`@Size`, `@Pattern`), allowlist enum parse, key normalization.
**PoC:**
```bash
curl -X POST "http://localhost:8080/api/test/events/$(python - <<'PY'
print('A'*50000)
PY
)/CARD_APPLY"
```

#### 6) Insecure deserialization hardening eksik
**Dosya:** `ApplicationConfig.java`, `EventConsumer.java`  
**Satır:** mapper bean + `readValue` kullanımı  
**Sorun:** Global ObjectMapper sertleştirme sınırlı; payload size/depth limit yok.  
**Risk:** JSON bomb/large payload ile memory pressure, parser abuse.  
**Çözüm:** Stream read constraints (`StreamReadConstraints`), request/message size limit, schema validation.
**PoC:**
```text
Aşırı derin nested JSON -> parse CPU/memory spike -> consumer lag.
```

### 🟡 Medium Priority (P2)

#### 7) Hata mesajları iç sistem detaylarını döndürüyor
**Dosya:** `TestController.java`, `DashboardController.java`  
**Satır:** catch blokları  
**Sorun:** `e.getMessage()` API response body’ye yazılıyor.  
**Risk:** SQL/Redis/Kafka hata detayı dış kullanıcıya açılır.  
**Çözüm:** RFC7807 standart generic error response + correlation id.

#### 8) Security event monitoring yetersiz
**Dosya:** Tüm codebase  
**Sorun:** Auth failure, abuse detection, anomaly scoring yok.  
**Risk:** Saldırı erken fark edilmez.
**Çözüm:** Security audit log + SIEM integration + threshold alerts.

### 🟢 Best Practices Missing
- TLS/mTLS ve Kafka SASL/SSL zorlaması görünmüyor.
- Endpoint rate limiting / API quota yok.
- Dependabot/SCA, SAST, secret scanning pipeline referansı yok.
- Data classification + PII masking standardı kodda tanımlı değil.

---

## ⚡ Performance Analysis

### Bottleneck #1: Senkron zincir (Kafka consume -> DB -> Redis -> Kafka -> DB)
**Dosya:** `CardApplicationOrchestrator.java`, `KafkaActionPublisher.java`, `PostgresEventStore.java`, `RedisStateStore.java`  
**Konum:** `process()` ve `publish()`  
**Sorun:** Her event için serial blocking I/O var; async/non-blocking yok.  
**Impact:** p95 latency artar, consumer throughput sınırlanır.  
**Measurement:**
- `event.process.latency` Timer (p50/p95/p99)
- External call breakdown (`redis.get`, `redis.set`, `db.insert`, `kafka.send`)  
**Fix:**
- `kafkaTemplate.send(...).completable()` callback/failure handling.
- Bulk/batch write ops where possible.
- Bounded retry + circuit breaker + timeout tuning.

### Bottleneck #2: Fire-and-forget Kafka publish, başarı teyidi yok
**Dosya:** `KafkaActionPublisher.java`  
**Konum:** `kafkaTemplate.send` sonrası immediate continue  
**Sorun:** Send sonucu beklenmiyor; başarısız publish sessizce geçilebilir.  
**Impact:** Audit/action consistency bozulur, incident tespiti zorlaşır.  
**Measurement:** `action.publish.success_rate`, `action.publish.failure_rate`, callback latency.
**Fix:** `ListenableFuture` callback ile başarı/fail metrik ve retry policy.

### Bottleneck #3: Dashboard sorgularında limitsiz scan eğilimi
**Dosya:** `PostgresEventStore.java`, `DashboardController.java`  
**Konum:** `countAll`, `countByEventType`, recent actions  
**Sorun:** Yüksek hacimde aggregation sorguları pahalı; cache/materialized view yok.  
**Impact:** Dashboard çağrıları DB’yi zorlar, p99 yükselir.  
**Measurement:** query latency histogram + slow query log.
**Fix:** Read replica, cached counters, periodic rollups.

### Scalability Concerns
- Consumer concurrency konfigüre edilmemiş (default tek thread davranışı riski).
- Backpressure stratejisi yok (lag büyürken load shedding yok).
- Redis pool/db pool sınırları var ancak saturation metrikleri izlenmiyor.

### Optimization Opportunities
- Journey state update için Lua script / optimistic locking (CAS).
- Action outbox pattern ile exactly-once benzeri güvenilirlik.
- Structured, sampled logs (high-volume debug azaltımı).

---

## 🐛 Production Risk Assessment

### Senaryo #1: Concurrent event race ile state corruption
**Tetikleyici:** Aynı customer için aynı partition dışında veya yeniden deneme ile yakın zamanlı event işleme.  
**Sonuç:** `getState` + `saveState` non-atomic olduğu için lost update; document count yanlış kalır.  
**Olasılık:** Orta-Yüksek  
**Impact:** P1  
**Prevention:** Redis WATCH/MULTI veya Lua CAS, version field, optimistic locking + dedup.

### Senaryo #2: Action publish kaybı (sessiz)
**Tetikleyici:** Kafka send fail veya transient network error.  
**Sonuç:** Idempotency key set edildiği için tekrar publish engellenir; action downstream’e hiç gitmez.  
**Olasılık:** Yüksek  
**Impact:** P0/P1 (müşteri bildirimi kaybı)  
**Prevention:** Outbox + transactional relay, or publish-success sonrası idempotency mark.

### Senaryo #3: Redis key-space abuse / memory pressure
**Tetikleyici:** Çok uzun/rasgele customerId ile sürekli test endpoint çağrısı.  
**Sonuç:** Redis’te çok sayıda state key, eviction ve sıcak key churn.  
**Olasılık:** Orta  
**Impact:** P1/P2  
**Prevention:** Input size limit, auth/rate limit, key sanitation.

### Edge Cases
- `CustomerEvent` UUID invariant dokümante ama constructor’da UUID formatı doğrulanmıyor.
- `EventType.valueOf(eventType)` null/invalid için exception fırlatıyor, bu beklenen ama abuse’a açık flood etkisi yaratabilir.
- Gelecek timestamp/çok eski timestamp validasyon yok (journey sırası bozulabilir).

### Error Handling Gaps
- `publishAction` exception swallow: iş kaybı var ama caller başarılı sanıyor.
- API error response’larda internal message expose ediliyor.
- DLQ publish fail durumunda sadece log var, secondary fallback yok.

---

## 🔥 Code Quality Review

### Smell #1: Magic number yoğunluğu
**Severity:** Major  
**Location:** `StateMachineEngine` (2 doc), `RedisStateStore` (30 gün), `KafkaActionPublisher` (24 saat), `KafkaConfig` (100 poll, 30 gün retention)  
**Pattern:** Hard-coded business/ops constants  
**Why it matters:** Operasyonel değişiklik için redeploy gerektirir, environment-specific tuning zorlaşır.  
**Refactoring:** `@ConfigurationProperties` ile merkezi `JourneyProperties`.

### Smell #2: Orchestrator’da reliability concern’lerin dağınık yönetimi
**Severity:** Major  
**Location:** `CardApplicationOrchestrator.process`  
**Pattern:** Core use-case içinde retry semantics ve partial failure kararları gömülü  
**Why it matters:** SRP zayıflar, incidentte davranış tahmini zorlaşır.  
**Refactoring:** Policy/Workflow step handler’ları ayır, outcome model (SUCCESS/PARTIAL/RETRYABLE_FAIL).

### Smell #3: Copy-paste SQL/Topic/Key string sabitleri
**Severity:** Minor  
**Location:** Kafka/Redis/Postgres adapter’ları  
**Pattern:** Dağınık constant yönetimi  
**Why it matters:** Drift ve config inconsistency riski.  
**Refactoring:** Centralized constants + typed config.

---

## 📊 Observability Gaps

## 📊 OBSERVABILITY MATURITY
**Current State:** 1.5/5  
- **Metrics:** 1/5 (özel business metric yok)  
- **Logging:** 2/5 (MDC var, structured JSON log ve redaction policy yok)  
- **Tracing:** 0/5 (distributed trace propagation yok)  
- **Alerting:** 0/5 (tanımlı eşik/alarmlar yok)

### Critical Missing Metrics

1) **Metric:** `journey.event.process.latency` (p50/p95/p99)  
**Why needed:** SLO ihlalini erken yakalamak için.  
**How:** Micrometer `Timer` ile `process()` etrafı.  
**Alert:** p95 > 500ms (5 dk) => P1.

2) **Metric:** `journey.state.transition.failures` (reason tag)  
**Why:** Business/infrastructure ayrımı görünür olur.  
**How:** catch bloklarında counter increment (`error_type` tag).  
**Alert:** fail rate > 1% => P1.

3) **Metric:** `kafka.consumer.lag`  
**Why:** Real-time guarantee takibi.  
**How:** Kafka client metrics binder + Burrow/Exporter.  
**Alert:** lag > 1000 (10 dk) => P1.

4) **Metric:** `redis.pool.usage`, `hikari.pool.usage`  
**Why:** Resource exhaustion’u outage öncesi yakalamak.  
**How:** Lettuce/Hikari metrics binders.  
**Alert:** usage > 80% => P2, >95% => P1.

5) **Metric:** `action.publish.outcome` (published/persisted/skipped/failed)  
**Why:** Sessiz action kaybını görünür kılar.  
**How:** publish path’in her kolunda counter.  
**Alert:** failed > 0.1% => P1.

### Logging Improvements
- JSON structured log + field-level masking (`customerId` hash).
- Slow log: DB/Redis/Kafka call >100ms.
- Retry count ve circuit breaker state değişimleri zorunlu log.

### Alerting Strategy
- Consumer lag, DLQ rate, transition fail ratio, pool saturation, action publish failure için SLO tabanlı alert.
- Alert runbook linkleri ve auto-remediation playbook.

---

## 🎓 Production Readiness Checklist
- [❌] Security reviewed (auth, secret, hardening eksik)
- [❌] Load tested
- [❌] Failover tested
- [❌] Monitoring complete
- [❌] Runbook prepared
- [⚠️] On-call trained (repoda kanıt yok)

---

## 💡 Öncelikli Aksiyon Listesi (Bu Hafta)
1. **P0:** `/api/test/**` ve `/dashboard/**` için authn/authz + prod’da test endpoint kapatma.
2. **P0:** Action publish akışını güvenilir hale getir (outbox veya publish-confirm + idempotency sırasını düzelt).
3. **P1:** Input validation + payload size limit + rate limiting.
4. **P1:** DLQ payload redaction, stacktrace kaldırma, security logging policy.
5. **P1:** Micrometer metric set + temel alert kuralları.

## 📝 Uzun Vadeli İyileştirmeler
- Distributed tracing (OpenTelemetry) ile Kafka→Redis→Postgres zinciri.
- Journey state için optimistic locking/CAS.
- Config/constant merkezi yönetimi + feature flags.
- Chaos & failure injection testleri (Redis down, Kafka partial outage).

---

## ✅ QUALITY CHECKLIST
- ✅ Tüm kritik dosyalar incelendi.
- ✅ Her kritik security issue için exploit senaryosu verildi.
- ✅ Performance issue’lar ölçüm önerisiyle verildi.
- ✅ Riskler prevention stratejileriyle sunuldu.
- ✅ Code smell’ler refactoring önerisi içeriyor.
- ✅ Monitoring gap’ler implementation yaklaşımıyla belirtildi.
- ✅ Önceliklendirme (P0/P1/P2) net.
- ✅ Aksiyonlar spesifik ve uygulanabilir.
