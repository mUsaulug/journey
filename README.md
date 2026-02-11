# Mini Banking Journey Orchestrator

Evam tarzı real-time customer journey orchestration sistemi. Hexagonal Architecture + Kafka + Redis + PostgreSQL ile production-ready banking journey yönetimi.

## Mimari Genel Bakış

```
┌─────────────────────────────────────────────────────────┐
│                    ADAPTERS (Dış Katman)                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────┐ │
│  │ Kafka       │  │ REST        │  │ Redis / Postgres │ │
│  │ Consumer    │  │ Controllers │  │ Adapters         │ │
│  └──────┬──────┘  └──────┬──────┘  └────────┬────────┘ │
│─────────┼────────────────┼──────────────────┼──────────│
│         │    APPLICATION (Orta Katman)       │          │
│  ┌──────▼──────────────────────────────────▼────────┐  │
│  │  ProcessEventUseCase ← CardApplicationOrchestrator│  │
│  │  StateStore / EventStore / ActionPublisher (Ports) │  │
│  │  StateMachineEngine                               │  │
│  └────────────────────┬──────────────────────────────┘  │
│───────────────────────┼────────────────────────────────│
│                       │  DOMAIN (İç Katman)            │
│  ┌────────────────────▼──────────────────────────────┐ │
│  │  Customer, CustomerEvent, CardApplicationState     │ │
│  │  Action, Segment, EventType, StateType             │ │
│  │  Pure Java — Zero Framework Dependency             │ │
│  └───────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

## Journey Flow (State Machine)

```
CARD_APPLY → APPLIED → DOCUMENT_PENDING → UNDER_REVIEW → APPROVED
                            ↑  ↓                          → REJECTED
                         DOCUMENT_UPLOAD
                        (2 belge gerekli)
```

## Gereksinimler

- **Java 17+**
- **Maven 3.8+**
- **Docker & Docker Compose**

## Kurulum ve Çalıştırma

### 1. Altyapı Servisleri

```bash
docker-compose up -d
```

Bu komut şu servisleri başlatır:
| Servis     | Port | Açıklama            |
|------------|------|---------------------|
| Kafka      | 9092 | Event streaming     |
| Zookeeper  | 2181 | Kafka coordination  |
| Redis      | 6379 | State management    |
| PostgreSQL | 5432 | Audit trail         |

### 2. Uygulama

```bash
mvn clean compile
mvn spring-boot:run
```

Uygulama `http://localhost:8080` adresinde çalışır.

### 3. Dashboard

Tarayıcıda `http://localhost:8080/dashboard.html` adresine gidin.

## API Kullanımı

### Test Event Gönderme

```bash
# 1. Kredi kartı başvurusu
curl -X POST "http://localhost:8080/api/test/events/cust-001/CARD_APPLY?segment=VIP"

# 2. Belge yükleme (1. belge)
curl -X POST "http://localhost:8080/api/test/events/cust-001/DOCUMENT_UPLOAD"

# 3. Belge yükleme (2. belge)
curl -X POST "http://localhost:8080/api/test/events/cust-001/DOCUMENT_UPLOAD"

# 4. Onay
curl -X POST "http://localhost:8080/api/test/events/cust-001/APPROVAL"
```

### State Sorgulama

```bash
curl http://localhost:8080/api/test/state/cust-001
```

### Dashboard İstatistikleri

```bash
curl http://localhost:8080/dashboard/stats
```

### Health Check

```bash
curl http://localhost:8080/api/test/health
```

## Proje Yapısı

```
src/main/java/com/banking/journey/
├── domain/                  # Pure Java (Zero framework imports)
│   ├── entity/              # Customer, CustomerEvent, CardApplicationState, Action
│   └── valueobject/         # Segment, EventType, StateType
├── application/             # Ports + Services
│   ├── port/in/             # ProcessEventUseCase
│   ├── port/out/            # StateStore, EventStore, ActionPublisher
│   └── service/             # CardApplicationOrchestrator, StateMachineEngine
├── adapters/                # Framework code
│   ├── in/kafka/            # EventConsumer (Kafka listener)
│   ├── in/rest/             # TestController, DashboardController
│   ├── out/redis/           # RedisStateStore
│   ├── out/postgres/        # PostgresEventStore
│   └── out/kafka/           # KafkaActionPublisher
└── bootstrap/               # Spring Boot wiring
    ├── JourneyOrchestratorApp.java
    └── config/              # ApplicationConfig, KafkaConfig, RedisConfig
```

## Teknik Detaylar

### Idempotency

- **Event Store**: `ON CONFLICT (event_id) DO NOTHING`
- **Action Publisher**: Redis `SETNX` ile duplicate action önleme (24 saat TTL)

### Error Handling (4 Katman)

| Hata Tipi    | Aksiyon       | Kafka Offset |
|--------------|---------------|--------------|
| Parse Error  | DLQ + skip    | Acknowledge  |
| Business     | DLQ + skip    | Acknowledge  |
| Transient    | Throw (retry) | NOT ack      |
| Unknown      | DLQ + skip    | Acknowledge  |

### Kafka Topics

| Topic               | Partitions | Retention |
|---------------------|------------|-----------|
| customer-events     | 10         | 7 gün     |
| actions             | 10         | 3 gün     |
| customer-events-dlq | 1          | 30 gün    |

### Redis Key Patterns

| Key Pattern                     | TTL    |
|---------------------------------|--------|
| `journey:state:{customerId}`   | 30 gün |
| `action:sent:{actionId}`       | 24 saat|
