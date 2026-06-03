package com.zxl.agi.infrastructure;

import com.zxl.agi.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
public class InfrastructureService {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AppConfig cfg;
    private Connection pgConn;

    private String milvusStatus = "disconnected";
    private String pgStatus = "disconnected";
    private String esStatus = "disconnected";
    private String kafkaStatus = "disconnected";

    public InfrastructureService(AppConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * 尝试连接所有基础设施，失败则降级为内存模式。
     */
    @PostConstruct
    public void init() {
        connectPostgres();
        connectMilvus();
        connectES();
        connectKafka();
    }

    // ─────────────────────────────── 连接初始化 ───────────────────────────────

    private void connectPostgres() {
        try {
            String url = cfg.getPgJdbcUrl();
            pgConn = DriverManager.getConnection(url, cfg.getPostgres().getUser(), cfg.getPostgres().getPassword());
            pgStatus = "connected";
            initPGSchema();
            log.info("✅ PostgreSQL 已连接: {}", url);
        } catch (Exception e) {
            log.warn("⚠️  PostgreSQL 连接失败: {} (将使用内存模式)", e.getMessage());
            pgStatus = "disconnected";
        }
    }

    private void connectMilvus() {
        // Milvus stub - always disconnected in this version
        log.warn("Milvus 连接跳过 (将使用内存向量库)");
        milvusStatus = "disconnected";
    }

    private void connectES() {
        // ES stub - always disconnected in this version
        log.warn("Elasticsearch 连接跳过 (将使用 TF 降级检索)");
        esStatus = "disconnected";
    }

    private void connectKafka() {
        // Kafka stub - always disconnected
        log.warn("Kafka 连接跳过 (事件将输出到日志)");
        kafkaStatus = "disconnected";
    }

    // ─────────────────────── PG ───────────────────────

    /**
     * PG表结构初始化
     */
    private void initPGSchema() {
        if (pgConn == null) return;
        String[] ddls = {
            """
            CREATE TABLE IF NOT EXISTS user_preferences (
                user_id TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL,
                updated_at TIMESTAMP DEFAULT NOW(), PRIMARY KEY (user_id, key))""",
            """
            CREATE TABLE IF NOT EXISTS task_snapshots (
                task_id TEXT PRIMARY KEY, state JSONB NOT NULL, created_at TIMESTAMP DEFAULT NOW())""",
            """
            CREATE TABLE IF NOT EXISTS chat_history (
                id SERIAL PRIMARY KEY, role TEXT NOT NULL, content TEXT NOT NULL, created_at TIMESTAMP DEFAULT NOW())""",
            """
            CREATE TABLE IF NOT EXISTS long_term_memory (
                id SERIAL PRIMARY KEY, content TEXT NOT NULL, importance FLOAT NOT NULL DEFAULT 0.5,
                embedding JSONB, created_at TIMESTAMP DEFAULT NOW(), last_accessed TIMESTAMP DEFAULT NOW())""",
            """
            CREATE TABLE IF NOT EXISTS rag_chunks (
                id BIGSERIAL PRIMARY KEY, doc_hash TEXT NOT NULL, chunk_idx INT NOT NULL,
                content TEXT NOT NULL, embedding JSONB, created_at TIMESTAMP DEFAULT NOW(),
                UNIQUE(doc_hash, chunk_idx))"""
        };
        try (Statement stmt = pgConn.createStatement()) {
            for (String ddl : ddls) stmt.execute(ddl);
            log.info("✅ PostgreSQL 表结构已初始化");
        } catch (SQLException e) {
            log.warn("⚠️ PostgreSQL 建表失败: {}", e.getMessage());
        }
    }

    // ===== Status =====
    public Map<String, String> getStatus() {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("milvus", milvusStatus);
        s.put("pg", pgStatus);
        s.put("elasticsearch", esStatus);
        s.put("kafka", kafkaStatus);
        return s;
    }

    public String getMilvusStatus() { return milvusStatus; }
    public String getPgStatus() { return pgStatus; }
    public String getEsStatus() { return esStatus; }
    public String getKafkaStatus() { return kafkaStatus; }

    /**
     * 持久化用户偏好到 PostgreSQL
     * @param userId
     * @param key
     * @param value
     */
    public void savePreference(String userId, String key, String value) {
        if (pgConn == null) return;
        try (PreparedStatement ps = pgConn.prepareStatement(
                "INSERT INTO user_preferences (user_id, key, value) VALUES (?, ?, ?) ON CONFLICT (user_id, key) DO UPDATE SET value = ?, updated_at = NOW()")) {
            ps.setString(1, userId); ps.setString(2, key); ps.setString(3, value); ps.setString(4, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("⚠️  偏好保存 PG 失败: {}", e.getMessage());
        }
    }

    /**
     * 从 PostgreSQL 加载指定用户的全部偏好
     * @param userId
     * @return
     */
    public Map<String, String> loadPreferences(String userId) {
        Map<String, String> result = new LinkedHashMap<>();
        if (pgConn == null) return result;
        try (PreparedStatement ps = pgConn.prepareStatement("SELECT key, value FROM user_preferences WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.put(rs.getString("key"), rs.getString("value"));
            }
        } catch (SQLException e) {
            log.warn("加载偏好失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 持久化任务快照到 PostgreSQL
     * @param taskId
     * @param stateJson
     */
    public void saveSnapshot(String taskId, String stateJson) {
        if (pgConn == null) return;
        try (PreparedStatement ps = pgConn.prepareStatement(
                "INSERT INTO task_snapshots (task_id, state) VALUES (?, ?::jsonb) ON CONFLICT (task_id) DO UPDATE SET state = ?::jsonb, created_at = NOW()")) {
            ps.setString(1, taskId); ps.setString(2, stateJson); ps.setString(3, stateJson);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("⚠️ 快照保存失败: {}", e.getMessage());
        }
    }

    /**
     * PG 长期记忆
     */
    public static class LongTermRow {
        public int id;
        public String content;
        public double importance;
        public List<Double> embedding;
        public Timestamp createdAt;
        public Timestamp lastAccessed;
    }

    /**
     * 持久化长久记忆到 PostgreSQL
     * @param content
     * @param importance
     * @param embeddingJson
     * @return
     */
    public int saveLongTermItem(String content, double importance, String embeddingJson) {
        if (pgConn == null) return -1;
        try (PreparedStatement ps = pgConn.prepareStatement(
                "INSERT INTO long_term_memory (content, importance, embedding) VALUES (?, ?, ?::jsonb) RETURNING id")) {
            ps.setString(1, content); ps.setDouble(2, importance); ps.setString(3, embeddingJson);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.warn("⚠️ 长期记忆保存失败: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * 加载全部长期记忆条目
     * @return
     */
    public List<LongTermRow> loadLongTermItems() {
        List<LongTermRow> items = new ArrayList<>();
        if (pgConn == null) return items;
        try (Statement stmt = pgConn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, content, importance, embedding, COALESCE(created_at, NOW()), COALESCE(last_accessed, NOW()) FROM long_term_memory ORDER BY id")) {
            while (rs.next()) {
                LongTermRow row = new LongTermRow();
                row.id = rs.getInt(1); row.content = rs.getString(2); row.importance = rs.getDouble(3);
                String embJson = rs.getString(4);
                if (embJson != null && !embJson.isEmpty()) {
                    try { row.embedding = mapper.readValue(embJson, mapper.getTypeFactory().constructCollectionType(List.class, Double.class)); } catch (Exception ignored) {}
                }
                row.createdAt = rs.getTimestamp(5); row.lastAccessed = rs.getTimestamp(6);
                items.add(row);
            }
        } catch (SQLException e) {
            log.warn("⚠️ 加载长期记忆失败: {}", e.getMessage());
        }
        return items;
    }

    /**
     * 长期记忆合并
     * 更新长期记忆条目的内容和重要性
     * @param id
     * @param content
     * @param importance
     * @param embeddingJson
     */
    public void updateLongTermItem(int id, String content, double importance, String embeddingJson) {
        if (pgConn == null) return;
        try (PreparedStatement ps = pgConn.prepareStatement(
                "UPDATE long_term_memory SET content = ?, importance = ?, embedding = ?::jsonb, last_accessed = NOW() WHERE id = ?")) {
            ps.setString(1, content); ps.setDouble(2, importance); ps.setString(3, embeddingJson); ps.setInt(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("长期记忆更新失败 (id={}): {}", id, e.getMessage());
        }
    }

    /**
     * 批量删除长期记忆条目
     * @param ids
     */
    public void deleteLongTermItems(List<Integer> ids) {
        if (pgConn == null || ids.isEmpty()) return;
        String placeholders = String.join(",", ids.stream().map(i -> "?").toArray(String[]::new));
        try (PreparedStatement ps = pgConn.prepareStatement("DELETE FROM long_term_memory WHERE id IN (" + placeholders + ")")) {
            for (int i = 0; i < ids.size(); i++) ps.setInt(i + 1, ids.get(i));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("长期记忆批量删除失败: {}", e.getMessage());
        }
    }

    // ─────────────────────── RAG Chunk 持久化 ───────────────────────

    /**
     * RAG Chunk
     */
    public static class ChunkRow {
        public long id;
        public String content;
    }

    /**
     * RAG chunk 持久化到 PostgreSQL
     * @param docHash
     * @param chunkIdx
     * @param content
     * @param embeddingJson
     * @return
     */
    public long saveRAGChunk(String docHash, int chunkIdx, String content, String embeddingJson) {
        if (pgConn == null) return -1;
        try (PreparedStatement ps = pgConn.prepareStatement(
                "INSERT INTO rag_chunks (doc_hash, chunk_idx, content, embedding) VALUES (?, ?, ?, ?::jsonb) " +
                "ON CONFLICT (doc_hash, chunk_idx) DO UPDATE SET content = EXCLUDED.content, embedding = EXCLUDED.embedding RETURNING id")) {
            ps.setString(1, docHash); ps.setInt(2, chunkIdx); ps.setString(3, content); ps.setString(4, embeddingJson);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            log.warn("⚠ RAG chunk 保存失败: {}", e.getMessage());
        }
        return -1;
    }

    /**
     * 从 PostgreSQL 加载全部 RAG chunk（用于启动时恢复 TF 索引）
     * @return
     */
    public List<ChunkRow> loadAllRAGChunks() {
        List<ChunkRow> chunks = new ArrayList<>();
        if (pgConn == null) return chunks;
        try (Statement stmt = pgConn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, content FROM rag_chunks ORDER BY id")) {
            while (rs.next()) {
                ChunkRow r = new ChunkRow(); r.id = rs.getLong(1); r.content = rs.getString(2);
                chunks.add(r);
            }
        } catch (SQLException e) {
            log.warn("加载 RAG chunks 失败: {}", e.getMessage());
        }
        return chunks;
    }

    /**
     *  按 ID 列表从 PostgreSQL 批量加载 RAG chunk
     * @param ids
     * @return
     */
    public List<ChunkRow> loadRAGChunksByIDs(List<Long> ids) {
        List<ChunkRow> chunks = new ArrayList<>();
        if (pgConn == null || ids.isEmpty()) return chunks;
        String placeholders = String.join(",", ids.stream().map(i -> "?").toArray(String[]::new));
        try (PreparedStatement ps = pgConn.prepareStatement("SELECT id, content FROM rag_chunks WHERE id IN (" + placeholders + ")")) {
            for (int i = 0; i < ids.size(); i++) ps.setLong(i + 1, ids.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChunkRow r = new ChunkRow(); r.id = rs.getLong(1); r.content = rs.getString(2);
                    chunks.add(r);
                }
            }
        } catch (SQLException e) {
            log.warn("按 ID 加载 RAG chunks 失败: {}", e.getMessage());
        }
        return chunks;
    }

    /**
     * 按 doc_hash 删除 PG 中的 RAG chunks
     * @param docHash
     * @return
     */
    public List<Long> deleteRAGChunksByDocHash(String docHash) {
        List<Long> ids = new ArrayList<>();
        if (pgConn == null) return ids;
        try (PreparedStatement ps = pgConn.prepareStatement("SELECT id FROM rag_chunks WHERE doc_hash = ?")) {
            ps.setString(1, docHash);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getLong(1));
            }
        } catch (SQLException e) { log.warn("查询 RAG chunks 失败: {}", e.getMessage()); }
        if (!ids.isEmpty()) {
            try (PreparedStatement ps = pgConn.prepareStatement("DELETE FROM rag_chunks WHERE doc_hash = ?")) {
                ps.setString(1, docHash); ps.executeUpdate();
            } catch (SQLException e) { log.warn("删除 RAG chunks 失败: {}", e.getMessage()); }
        }
        return ids;
    }

    // ─────────────────────────────── 生命周期 ────────────────────────────────

    /**
     * 持久化聊天记录到 PostgreSQL
     * @param role
     * @param content
     */
    public void saveChatHistory(String role, String content) {
        if (pgConn == null) return;
        try (PreparedStatement ps = pgConn.prepareStatement("INSERT INTO chat_history (role, content) VALUES (?, ?)")) {
            ps.setString(1, role); ps.setString(2, content); ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("聊天记录保存失败: {}", e.getMessage());
        }
    }

    public static class ChatHistoryRow {
        public String role;
        public String content;
        public String createdAt;
    }

    /**
     * 从 PostgreSQL 加载最近 N 条聊天记录
     * @param limit
     * @return
     */
    public List<ChatHistoryRow> loadChatHistory(int limit) {
        List<ChatHistoryRow> rows = new ArrayList<>();
        if (pgConn == null) return rows;
        try (PreparedStatement ps = pgConn.prepareStatement(
                "SELECT role, content, TO_CHAR(created_at, 'HH24:MI:SS') FROM chat_history ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChatHistoryRow r = new ChatHistoryRow();
                    r.role = rs.getString(1); r.content = rs.getString(2); r.createdAt = rs.getString(3);
                    rows.add(r);
                }
            }
        } catch (SQLException e) {
            log.warn("⚠️ 加载聊天记录失败: {}", e.getMessage());
        }
        Collections.reverse(rows);
        return rows;
    }

    // ES TODO

    // ===== Kafka (stub) =====
    public void publishEvent(String eventType, String payload) {
        if ("connected".equals(kafkaStatus)) {
            // Real kafka publish would go here
        } else {
            log.info("[Kafka-fallback] {}: {}", eventType, payload);
        }
    }

    // ===== RAG Infra Init (stub) =====
    public void initRAGInfra(int dim) {
        // Milvus collection and ES index creation stubs
        log.info("RAG 基础设施初始化完成 (Milvus/ES 未连接，使用 TF 降级)");
    }

    @PreDestroy
    public void close() {
        if (pgConn != null) {
            try { pgConn.close(); } catch (SQLException ignored) {}
        }
    }
}
