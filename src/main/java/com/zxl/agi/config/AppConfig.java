package com.zxl.agi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 统一配置类
 */
@Component
@ConfigurationProperties(prefix = "app")
@Data
public class AppConfig {

    private LlmConfig llm = new LlmConfig();
    private EmbeddingConfig embedding = new EmbeddingConfig();
    private MilvusConfig milvus = new MilvusConfig();
    private PostgresConfig postgres = new PostgresConfig();
    private ElasticsearchConfig elasticsearch = new ElasticsearchConfig();
    private KafkaConfig kafka = new KafkaConfig();
    private RagConfig rag = new RagConfig();
    private MemoryConfig memory = new MemoryConfig();
    private HarnessConfig harness = new HarnessConfig();
    private SearchConfig search = new SearchConfig();

    // ===== Inner Config Classes =====
    @Data
    public static class LlmConfig {
        private String apiUrl;
        private String apiKey;
        private String model;
        private double temperature = 0.7;
    }

    @Data
    public static class EmbeddingConfig {
        private String apiUrl;
        private String apiKey;
        private String model;
    }

    @Data
    public static class MilvusConfig {
        private String host = "localhost";
        private int port = 19530;
    }

    @Data
    public static class PostgresConfig {
        private String host = "localhost";
        private int port = 5432;
        private String user = "aiagent";
        private String password = "aiagent123";
        private String database = "aiagent";
    }

    @Data
    public static class ElasticsearchConfig {
        private String addresses = "http://localhost:9200";
        private String username = "elastic";
        private String password = "changeme";
    }

    @Data
    public static class KafkaConfig {
        private String brokers = "localhost:29092";
        private String topic = "agent-events";
    }

    @Data
    public static class RagConfig {
        private int chunkSize = 200;
        private int chunkOverlap = 50;
        private int topK = 3;
        private int rrfConstantK = 60;
        private double semanticWeight = 0.7;
        private boolean enableHybridSearch = true;
        private int ragMilvusDim = 1024;
    }

    @Data
    public static class MemoryConfig {
        private int shortTermMaxTurns = 5;
        private int longTermTopK = 3;
        private ConsolidationConfig consolidation = new ConsolidationConfig();
    }

    @Data
    public static class ConsolidationConfig {
        /**
         * 合并相似度阈值 (0~1)，超过此值触发合并
         */
        private double similarityThreshold = 0.80;

        /**
         * 去重相似度阈值 (0~1)，超过此值视为重复
         */
        private double dedupThreshold = 0.95;

        /**
         * 过期天数 (0=永不过期)
         */
        private int ttlDays = 30;

        /**
         * 每日衰减系数 (0~1, 如 0.995 表示每天保留 99.5%)
         */
        private double decayRate = 0.995;

        /**
         * 低于此重要性且超 TTL 的条目会被淘汰
         */
        private double minImportance = 0.3;

        /**
         * 每存入 N 条新记忆后触发合并
         */
        private int triggerInterval = 5;
    }

    @Data
    public static class HarnessConfig {
        private int maxRetries = 3;
        private int retryDelayMs = 200;
        private int stepTimeoutMs = 5000;
        private int maxIterations = 5;
    }

    @Data
    public static class SearchConfig {
        private String apiKey;
        private String apiUrl;
    }

    // ===== Helper Methods =====

    public boolean isRealLLM() {
        return llm.getApiKey() != null && !llm.getApiKey().isEmpty();
    }

    public boolean isRealEmbedding() {
        return embedding.getApiKey() != null && !embedding.getApiKey().isEmpty();
    }

    public String getPgJdbcUrl() {
        return String.format("jdbc:postgresql://%s:%d/%s",
                postgres.getHost(), postgres.getPort(), postgres.getDatabase());
    }

    public String getMilvusAddr() {
        return String.format("%s:%d", milvus.getHost(), milvus.getPort());
    }
}
