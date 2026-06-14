package com.zxl.agi.service.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.zxl.agi.config.AppConfig;
import com.zxl.agi.infrastructure.InfrastructureService;
import com.zxl.agi.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private Function<String, List<Float>> embedFn;
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
            List<Float> emb = null;
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
                embeddings.add(emb);
            }
        }

        // 批量写Milvus
        if (!pgIds.isEmpty()) {
            try {
                infra.insertVectors("rag_chunks", pgIds, embeddings
                );
            } catch (Exception e) {
                log.warn("RAG chunks写入Milvus失败 exception: {}", e.getMessage());
            }
        }

        return docHash;
    }

    /**
     * 计算文档哈希
     * @param input
     * @return
     */
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
                infra.deleteVectors("rag_chunks", pgIds);
            } catch (Exception e) {
                log.warn("Milvus 删除 RAG chunks 失败: {}", e.getMessage());
            }
        }
    }

    // ─────────────────────── Search入口 ──────────────────────────────
    public List<HybridResult> search(String query, int topK) {

        return switch (mode) {
            case "hybrid" -> searchHybrid(query, topK);
            case "semantic" -> searchSemantic(query, topK);
            case "keyword" -> searchKeyword(query, topK);
            default -> {
                log.warn("⚠️ 检索基础设施不可用（Milvus 和 ES 均未连接）");
                yield Collections.emptyList();
            }
        };
    }

    // ─────────────────────── Hybrid (RRF融合) ──────────────────────────────
    /**
     *  Milvus 语义 + ES BM25，使用 Reciprocal Rank Fusion 融合
     * @param query
     * @param topK
     * @return
     */
    private List<HybridResult> searchHybrid(String query, int topK) {
        // 查询向量化
        List<Float> queryEmb;
        try {
            queryEmb = embedFn.apply(query);
        } catch (Exception e) {
            log.warn("⚠️ 查询向量化失败，降级关键词: {}", e.getMessage());
            return searchKeyword(query, topK);
        }

        // 从两路各取 2*topK 保证融合后有足够候选
        int fetchK = Math.max(topK * 2, 10);

        List<MilvusHit> milvusHits;
        List<ESHit> esHits;

        try {
            milvusHits = infra.searchVectors("rag_chunks", queryEmb, fetchK);
        } catch (Exception e) {
            log.warn("⚠️ Milvus检索失败，降级ES使用关键词检索: {}", e.getMessage());
            return searchKeyword(query, topK);
        }

        try {
            esHits = infra.searchRagChunks(query, fetchK);
        } catch (Exception e) {
            log.warn("⚠️ ES检索失败，使用语义检索: {}", e.getMessage());
            return searchSemantic(query, topK);
        }

        // RRF fusion：: score(d) = Σ 1/(k + rank_i(d))
        Map<Long, Double> rrfScores = new HashMap<>();
        int rrfK = cfg.getRag().getRrfConstantK();
        for (int rank = 0; rank < milvusHits.size(); rank++) {
            MilvusHit hit = milvusHits.get(rank);
            rrfScores.merge(
                    hit.getId(),
                    1.0 / (rrfK + rank + 1),
                    Double::sum
            );
        }

        for (int rank = 0; rank < esHits.size(); rank++) {
            ESHit hit = esHits.get(rank);
            rrfScores.merge(
                    hit.getPgId(),
                    1.0 / (rrfK + rank + 1),
                    Double::sum
            );
        }


        // 按 RRF 分数排序
        List<Long> sortedIds = rrfScores.entrySet()
                .stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .toList();

        // 从 PG 批量取回 chunk 内容
        Map<Long, String> contentMap = loadContent(sortedIds);

        return sortedIds.stream()
                .map(id -> new HybridResult(
                        new Chunk(id, contentMap.get(id)),
                        rrfScores.get(id),
                        "hybrid"
                ))
                .filter(r -> r.getChunk().getContent() != null)
                .toList();
    }

    /**
     * PG批量回填
     * @param ids
     * @return
     */
    private Map<Long, String> loadContent(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            return infra.loadRAGChunksByIDs(ids)
                    .stream()
                    .collect(Collectors.toMap(
                            Chunk::getId,
                            Chunk::getContent,
                            (a, b) -> a
                    ));
        } catch (Exception e) {
            log.warn("⚠️ PG加载失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Elasticsearch BM25 关键词检索
     * @param query
     * @param topK
     * @return
     */
    private List<HybridResult> searchKeyword(String query, int topK) {
        List<ESHit> hits;
        try {
            hits = infra.searchRagChunks(query, topK);
        } catch (Exception e) {
            log.warn("⚠️ ES失败: {}", e.getMessage());
            return Collections.emptyList();
        }

        List<Long> ids = hits.stream()
                .map(ESHit::getPgId)
                .toList();

        Map<Long, String> contentMap = loadContent(ids);

        return hits.stream()
                .map(h -> new HybridResult(
                        new Chunk(h.getPgId(), contentMap.get(h.getPgId())),
                        h.getScore(),
                        "keyword"
                ))
                .toList();
    }

    /**
     * Milvus 语义向量检索
     * @param query
     * @param topK
     * @return
     */
    private List<HybridResult> searchSemantic(String query, int topK) {
        List<Float> emb;
        try {
            emb = embedFn.apply(query);
        } catch (Exception e) {
            log.warn("⚠️ embedding失败: {}", e.getMessage());
            return Collections.emptyList();
        }

        List<MilvusHit> hits;
        try {
            hits = infra.searchVectors("rag_chunks", emb, topK);
        } catch (Exception e) {
            log.warn("⚠️ Milvus失败: {}", e.getMessage());
            return Collections.emptyList();
        }

        List<Long> ids = hits.stream()
                .map(MilvusHit::getId)
                .toList();

        Map<Long, String> contentMap = loadContent(ids);

        return hits.stream()
                .map(h -> new HybridResult(
                        new Chunk(h.getId(), contentMap.get(h.getId())),
                        (double) h.getDistance(),
                        "semantic"
                ))
                .toList();
    }
}
