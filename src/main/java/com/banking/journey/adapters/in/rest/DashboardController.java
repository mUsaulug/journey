package com.banking.journey.adapters.in.rest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.banking.journey.application.port.out.ActionPublisher;
import com.banking.journey.application.port.out.EventStore;
import com.banking.journey.bootstrap.config.JourneyProperties;
import com.banking.journey.domain.entity.Action;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final EventStore eventStore;
    private final ActionPublisher actionPublisher;
    private final int defaultRecentActionLimit;

    public DashboardController(EventStore eventStore,
            ActionPublisher actionPublisher,
            JourneyProperties journeyProperties) {
        this.eventStore = eventStore;
        this.actionPublisher = actionPublisher;
        this.defaultRecentActionLimit = Math.max(1, journeyProperties.getDashboard().getRecentActionsLimit());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(name = "recentLimit", required = false) Integer recentLimit) {
        try {
            long totalEvents = eventStore.countAll();
            long totalActions = actionPublisher.countAll();

            int effectiveLimit = normalizeLimit(recentLimit);

            List<Object[]> typeCounts = eventStore.countByEventType();
            List<Map<String, Object>> eventDistribution = typeCounts.stream()
                    .map(row -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("eventType", row[0]);
                        item.put("count", row[1]);
                        return item;
                    })
                    .collect(Collectors.toList());

            List<Action> recentActions = actionPublisher.getRecentActions(effectiveLimit);
            List<Map<String, Object>> recentActionsList = recentActions.stream()
                    .map(action -> {
                        Map<String, Object> item = new HashMap<>();
                        item.put("actionId", action.getActionId());
                        item.put("customerId", action.getCustomerId());
                        item.put("actionType", action.getActionType());
                        item.put("message", action.getMessage());
                        item.put("channel", action.getChannel());
                        item.put("createdAt", action.getCreatedAt().toString());
                        return item;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> stats = Map.of(
                    "totalEvents", totalEvents,
                    "totalActions", totalActions,
                    "eventTypeDistribution", eventDistribution,
                    "recentActions", recentActionsList,
                    "recentActionsLimit", effectiveLimit);

            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("action=dashboard_stats_error error={}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Unable to load dashboard stats"));
        }
    }

    private int normalizeLimit(Integer limit) {
        int candidate = limit != null ? limit : defaultRecentActionLimit;
        return Math.min(Math.max(candidate, 1), 100);
    }
}
