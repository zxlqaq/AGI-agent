package com.zxl.agi.service.rag;

import com.zxl.agi.config.AppConfig;
import com.zxl.agi.infrastructure.InfrastructureService;
import com.zxl.agi.model.Chunk;
import com.zxl.agi.model.HybridResult;
import com.zxl.agi.model.RagQueryResult;
import com.zxl.agi.model.SearchResult;
import com.zxl.agi.service.memory.LongTermMemory;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * RAGService - rag 实现检索增强生成
 * 包含：文本分割器、混合检索存储（Milvus 语义 + ES BM25 + RRF 融合）、RAG 引擎。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final AppConfig cfg;
    private final HybridStore store;
    private final TextSplitter splitter;
    private final InfrastructureService infra;

    @Getter
    private boolean loaded = false;
    @Setter
    private BiFunction<String, String, String> generateFn;
    private Function<String, List<Float>> embedFn;

    // In-memory TF index (fallback when Milvus/ES unavailable)
    private final List<Chunk> indexedChunks = Collections.synchronizedList(new ArrayList<>());

    public RagService(AppConfig cfg, HybridStore store, TextSplitter splitter, InfrastructureService infra) {
        this.cfg = cfg;
        this.store = store;
        this.splitter = splitter;
        this.infra = infra;
        this.splitter.setChunkSize(cfg.getRag().getChunkSize());
        this.splitter.setOverlap(cfg.getRag().getChunkOverlap());
    }

    public void setEmbedFn(Function<String, List<Float>> fn) {
        this.embedFn = fn;
        this.store.setEmbedFn(fn);
    }

    public String getMode() {
        return store.getMode();
    }

    /**
     * Ingest a document: split, index, return (chunkCount, docHash)
     */
    public Map.Entry<Integer, String> ingest(String doc) {
        List<Chunk> chunks = splitter.split(doc);
        String docHash = store.index(chunks, doc);
        indexedChunks.addAll(chunks);
        loaded = true;
        infra.publishEvent("rag.ingest",
                String.format("{\"chunk_count\":%d,\"mode\":\"%s\",\"doc_hash\":\"%s\"}", chunks.size(), store.getMode(), docHash));
        return Map.entry(chunks.size(), docHash);
    }

    /**
     * Delete 按 docHash 删除文档的所有 chunks（PG + ES + Milvus）
     */
    public void delete(String docHash) {
        store.delete(docHash);
        // 删除后检查是否还有 chunks
        List<Chunk> rows = infra.loadAllRAGChunks();
        indexedChunks.clear();
        for (int i = 0; i < rows.size(); i++) {
            indexedChunks.add(new Chunk((long) i, rows.get(i).getContent()));
        }
        loaded = !rows.isEmpty();
    }

    /**
     * Query the knowledge base and return (answer, searchResults)
     */
    public RagQueryResult query(String question) {
        if (!loaded) {
            return new RagQueryResult(
                    false,
                    0D,
                    "知识库为空，请先上传文档。",
                    Collections.emptyList()
            );
        }

        // TF-based search (fallback when Milvus/ES unavailable)
//        List<ScoredChunk> results = tfSearch(question, cfg.getRag().getTopK());
        List<HybridResult> hybridResults = store.search(question, cfg.getRag().getTopK());
        if (hybridResults.isEmpty()) {
            return new RagQueryResult(
                    false,
                    0D,
                    "知识库中未找到相关内容。",
                    Collections.emptyList()
            );
        }

        List<SearchResult> results = hybridResults.stream()
                .map(hr -> new SearchResult(hr.getChunk(), hr.getScore()))
                .toList();

        // 计算最高召回分数
        double maxScore = hybridResults.stream()
                .mapToDouble(HybridResult::getScore)
                .max()
                .orElse(0D);

        String context = results.stream()
                .map(SearchResult::getChunk)
                .filter(Objects::nonNull)
                .map(Chunk::getContent)
                .filter(content -> content != null && !content.isBlank())
                .collect(Collectors.joining("\n\n"));

        // 未命中知识库
        if (context.isBlank()) {
            return new RagQueryResult(
                    false,
                    maxScore,
                    "知识库中未命中！",
                    results
            );
        }

        // 命中知识库
        if (generateFn != null) {
            String systemPrompt = "你是一个基于知识库回答问题的助手。" +
                    "请仅根据提供的上下文内容回答问题，不要编造信息。" +
                    "如果上下文不足以回答，请说明。";
            String userMsg = String.format("上下文：\n%s\n\n问题：%s", context, question);
            String answer = generateFn.apply(systemPrompt, userMsg);
            return new RagQueryResult(
                    true,
                    maxScore,
                    answer,
                    results
            );
        }

        // 无LLM时直接返回原文
        return new RagQueryResult(
                true,
                maxScore,
                "【知识库检索结果】\n" + context,
                results);
    }

    /**
     * Get all chunks (for status API).
     */
    public List<Chunk> getChunks() {
        List<Chunk> rows = infra.loadAllRAGChunks();
        if (!rows.isEmpty()) {
            List<Chunk> chunks = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                chunks.add(new Chunk((long) i, rows.get(i).getContent()));
            }
            return chunks;
        }
        return new ArrayList<>(indexedChunks);
    }

    /**
     * Restore chunks from DB on startup.
     */
    public void restoreChunks(List<Chunk> chunks) {
        indexedChunks.clear();
        indexedChunks.addAll(chunks);
        loaded = !chunks.isEmpty();
    }

    // ===== TF-based search (fallback) =====

    private List<SearchResult> tfSearch(String query, int topK) {
        if (indexedChunks.isEmpty()) return Collections.emptyList();

        // Build vocab
        Set<String> allTokens = new LinkedHashSet<>();
        List<String> queryTokens = LongTermMemory.tokenize(query);
        allTokens.addAll(queryTokens);
        for (Chunk chunk : indexedChunks) {
            allTokens.addAll(LongTermMemory.tokenize(chunk.getContent()));
        }
        List<String> vocabList = new ArrayList<>(allTokens);
        Map<String, Integer> vocabIdx = new HashMap<>();
        for (int i = 0; i < vocabList.size(); i++) vocabIdx.put(vocabList.get(i), i);

        // Query vector
        double[] qVec = new double[vocabList.size()];
        for (String t : queryTokens) {
            Integer idx = vocabIdx.get(t);
            if (idx != null) qVec[idx]++;
        }

        // Score chunks
        List<SearchResult> scored = new ArrayList<>();
        for (Chunk chunk : indexedChunks) {
            double[] cVec = new double[vocabList.size()];
            for (String t : LongTermMemory.tokenize(chunk.getContent())) {
                Integer idx = vocabIdx.get(t);
                if (idx != null) cVec[idx]++;
            }
            double sim = cosine(qVec, cVec);
            if (sim > 0) scored.add(new SearchResult(chunk, sim));
        }

        scored.sort((a, b) -> Double.compare(b.getSimilarity(), a.getSimilarity()));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    private double cosine(double[] a, double[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ===== Result types =====

//    public static class ScoredChunk {
//        public Chunk chunk;
//        public double score;
//        public ScoredChunk(Chunk chunk, double score) { this.chunk = chunk; this.score = score; }
//    }
//
//    public static class QueryResult {
//        public String answer;
//        public List<ScoredChunk> results;
//        public QueryResult(String answer, List<ScoredChunk> results) {
//            this.answer = answer; this.results = results;
//        }
//    }
}
