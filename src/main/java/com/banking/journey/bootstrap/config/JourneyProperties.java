package com.banking.journey.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "journey")
public class JourneyProperties {

    private int requiredDocumentCount = 2;
    private final Kafka kafka = new Kafka();
    private final Redis redis = new Redis();
    private final Dashboard dashboard = new Dashboard();

    public int getRequiredDocumentCount() {
        return requiredDocumentCount;
    }

    public void setRequiredDocumentCount(int requiredDocumentCount) {
        this.requiredDocumentCount = requiredDocumentCount;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public Redis getRedis() {
        return redis;
    }

    public Dashboard getDashboard() {
        return dashboard;
    }

    public static class Kafka {
        private final Topics topics = new Topics();
        private int partitions = 10;
        private int replicationFactor = 1;
        private int dlqRetentionDays = 30;
        private long publishAckTimeoutMs = 3000;
        private int consumerMaxPollRecords = 100;
        private int consumerMaxPollIntervalMs = 300000;
        private int consumerSessionTimeoutMs = 30000;

        public Topics getTopics() {
            return topics;
        }

        public int getPartitions() {
            return partitions;
        }

        public void setPartitions(int partitions) {
            this.partitions = partitions;
        }

        public int getReplicationFactor() {
            return replicationFactor;
        }

        public void setReplicationFactor(int replicationFactor) {
            this.replicationFactor = replicationFactor;
        }


        public int getDlqRetentionDays() {
            return dlqRetentionDays;
        }

        public void setDlqRetentionDays(int dlqRetentionDays) {
            this.dlqRetentionDays = dlqRetentionDays;
        }


        public int getConsumerMaxPollRecords() {
            return consumerMaxPollRecords;
        }

        public void setConsumerMaxPollRecords(int consumerMaxPollRecords) {
            this.consumerMaxPollRecords = consumerMaxPollRecords;
        }

        public int getConsumerMaxPollIntervalMs() {
            return consumerMaxPollIntervalMs;
        }

        public void setConsumerMaxPollIntervalMs(int consumerMaxPollIntervalMs) {
            this.consumerMaxPollIntervalMs = consumerMaxPollIntervalMs;
        }

        public int getConsumerSessionTimeoutMs() {
            return consumerSessionTimeoutMs;
        }

        public void setConsumerSessionTimeoutMs(int consumerSessionTimeoutMs) {
            this.consumerSessionTimeoutMs = consumerSessionTimeoutMs;
        }

        public long getPublishAckTimeoutMs() {
            return publishAckTimeoutMs;
        }

        public void setPublishAckTimeoutMs(long publishAckTimeoutMs) {
            this.publishAckTimeoutMs = publishAckTimeoutMs;
        }
    }

    public static class Topics {
        private String customerEvents = "customer-events";
        private String actions = "actions";
        private String dlq = "customer-events-dlq";

        public String getCustomerEvents() {
            return customerEvents;
        }

        public void setCustomerEvents(String customerEvents) {
            this.customerEvents = customerEvents;
        }

        public String getActions() {
            return actions;
        }

        public void setActions(String actions) {
            this.actions = actions;
        }

        public String getDlq() {
            return dlq;
        }

        public void setDlq(String dlq) {
            this.dlq = dlq;
        }
    }

    public static class Redis {
        private String statePrefix = "journey:state:";
        private String idempotencyPrefix = "action:status:";
        private long stateTtlDays = 30;
        private long idempotencyTtlHours = 24;
        private long processingTtlMinutes = 5;

        public String getStatePrefix() {
            return statePrefix;
        }

        public void setStatePrefix(String statePrefix) {
            this.statePrefix = statePrefix;
        }

        public String getIdempotencyPrefix() {
            return idempotencyPrefix;
        }

        public void setIdempotencyPrefix(String idempotencyPrefix) {
            this.idempotencyPrefix = idempotencyPrefix;
        }

        public long getStateTtlDays() {
            return stateTtlDays;
        }

        public void setStateTtlDays(long stateTtlDays) {
            this.stateTtlDays = stateTtlDays;
        }

        public long getIdempotencyTtlHours() {
            return idempotencyTtlHours;
        }

        public void setIdempotencyTtlHours(long idempotencyTtlHours) {
            this.idempotencyTtlHours = idempotencyTtlHours;
        }

        public long getProcessingTtlMinutes() {
            return processingTtlMinutes;
        }

        public void setProcessingTtlMinutes(long processingTtlMinutes) {
            this.processingTtlMinutes = processingTtlMinutes;
        }
    }

    public static class Dashboard {
        private int recentActionsLimit = 10;
        private int maxRecentActionsLimit = 100;

        public int getRecentActionsLimit() {
            return recentActionsLimit;
        }

        public void setRecentActionsLimit(int recentActionsLimit) {
            this.recentActionsLimit = recentActionsLimit;
        }

        public int getMaxRecentActionsLimit() {
            return maxRecentActionsLimit;
        }

        public void setMaxRecentActionsLimit(int maxRecentActionsLimit) {
            this.maxRecentActionsLimit = maxRecentActionsLimit;
        }
    }
}
