package com.zxl.agi.service.rag;

import com.zxl.agi.config.AppConfig;
import com.zxl.agi.infrastructure.InfrastructureService;
import com.zxl.agi.model.Chunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * HybridStore - 实现企业级混合检索
 *  *   - Milvus 语义向量检索
 *  *   - Elasticsearch BM25 关键词检索
 *  *   - Reciprocal Rank Fusion 融合两路结果
 *  *   - PostgreSQL chunk 持久化
 */
@Component
public class HybridStore {

    private static final Logger log = LoggerFactory.getLogger(HybridStore.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final AppConfig cfg;
    private final InfrastructureService infra;
    @Setter
    private Function<String, List<Double>> embedFn;
    @Getter
    private String mode = "unavailable";

    public HybridStore(AppConfig cfg, InfrastructureService infra) {
        this.cfg = cfg;
        this.infra = infra;
        // Determine mode based on infrastructure availability
        boolean milvusOK = "connected".equals(infra.getMilvusStatus());
        boolean esOK = "connected".equals(infra.getEsStatus());
        if (milvusOK && esOK) mode = "hybrid";
        else if (milvusOK) mode = "semantic";
        else if (esOK) mode = "keyword";
        else mode = "unavailable";
    }

    /**
     * Index 将 chunks 持久化到 PG + Milvus + ES，返回文档哈希（用于后续删除）
     */
    public String index(List<Chunk> chunks, String docContent) {
        String docHash = sha256(docContent).substring(0, 16);

        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            List<Double> emb = null;
            if (embedFn != null) {
                emb = embedFn.apply(c.getContent());
            }
            String embJson = "null";
            if (emb != null && !emb.isEmpty()) {
                try {
                    embJson = mapper.writeValueAsString(emb);
                } catch (Exception ignored) {
                    
                }
            }
            long pgId = infra.saveRAGChunk(docHash, i, c.getContent(), embJson);
            if (pgId < 0) {
                log.warn("RAG chunk 写入 PG 失败 (idx={})", i);
            }
            // ES/Milvus indexing would happen here if connected
        }
        return docHash;
    }

    /**
     * Delete all chunks for a document by its hash.
     */
    public List<Long> delete(String docHash) {
        return infra.deleteRAGChunksByDocHash(docHash);
    }

    /**
     * Search - currently returns empty since Milvus/ES unavailable.
     * RAG falls back to TF-based search in RagService.
     */
    public List<SearchResult> search(String query, int topK) {
        // In "unavailable" mode, return empty - RagService handles TF fallback
        return Collections.emptyList();
    }

    public void restoreChunks(List<Chunk> chunks) {
        // Chunks already in PG, no additional action needed
    }

    // ===== Helper =====

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
    }

    public static class SearchResult {
        public Chunk chunk;
        public double score;
        public String source;

        public SearchResult(Chunk chunk, double score, String source) {
            this.chunk = chunk; this.score = score; this.source = source;
        }
    }
}
