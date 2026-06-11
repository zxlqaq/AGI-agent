package com.zxl.agi.service.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.zxl.agi.config.AppConfig;
import com.zxl.agi.infrastructure.InfrastructureService;
import com.zxl.agi.model.Chunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
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
        // 计算文档哈希（幂等摄入）
        String docHash = sha256(docContent);

        List<Long> pgIds = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        List<List<Float>> embeddings = new ArrayList<>();

        // 遍历处理每个 Chunk
        for (int i = 0; i < chunks.size(); i++) {
            // Embedding 向量化
            Chunk c = chunks.get(i);
            List<Double> emb = null;
            String embJson = "null"; // Go 中 json.Marshal(nil) 结果为 "null"
            if (embedFn != null) {
                try {
                    emb = embedFn.apply(c.getContent());
                    embJson = mapper.writeValueAsString(emb);
                } catch (JsonProcessingException e) {
                    log.warn("⚠️ RAG chunk embedding JSON序列化失败 (idx={}): {}", i, e.getMessage());
                }
            }

            // 持久化到 PostgreSQL
            long pgId = infra.saveRAGChunk(docHash, i, c.getContent(), embJson);
            if (pgId < 0) {
                log.warn("RAG chunk 写入 PG 失败 (idx={})", i);
            }

            // 索引到 Elasticsearch
            if ("connected".equals(infra.getEsStatus())) {
                try {
                    infra.indexRagChunk(pgId, c.getContent(), docHash, i);
                } catch (Exception e) {
                    log.warn("RAG chunk索引ES失败 pgId={}, error={}", pgId, e.getMessage());
                }
            }
            // 收集 Milvus 批量写入数据
            if ("connected".equals(infra.getMilvusStatus())
                    && emb != null
                    && !emb.isEmpty()) {
                pgIds.add(pgId);
                contents.add(c.getContent());
                List<Float> vector = emb.stream()
                        .map(Double::floatValue)
                        .toList();

                embeddings.add(vector);
            }
        }

        // 批量写Milvus
        if (!pgIds.isEmpty()) {
            try {
                infra.insertRagChunks(
                        pgIds,
                        contents,
                        embeddings
                );
            } catch (Exception e) {
                log.warn("RAG chunks写入Milvus失败 exception: {}", e.getMessage());
            }
        }

        return docHash;
    }

    /**
     * Delete 按 docHash 删除文档的所有 chunks（PG + ES + Milvus）
     */
    public void delete(String docHash) {
        // 删除PG中的Chunk并返回对应PGID
        List<Long> pgIds = infra.deleteRAGChunksByDocHash(docHash);
        if (pgIds.isEmpty()) {
            return;
        }

        // 删除ES索引
        if ("connected".equals(infra.getEsStatus())) {
            try {
                infra.deleteRagChunksFromEs(pgIds);
            } catch (Exception e) {
                log.warn("ES 删除 RAG chunks 失败: {}", e.getMessage());
            }
        }

        // 删除Milvus向量
        if ("connected".equals(infra.getEsStatus())) {
            try {
                infra.deleteRagChunksFromMilvus(pgIds);
            } catch (Exception e) {
                log.warn("Milvus 删除 RAG chunks 失败: {}", e.getMessage());
            }
        }
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
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.substring(0, 16);
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
