package com.zxl.agi.service.memory;

import com.zxl.agi.config.AppConfig;
import com.zxl.agi.model.ConsolidationResult;
import com.zxl.agi.model.LongTermMemoryItem;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 长期记忆：支持：
 * 1. 语义向量召回
 * 2. TF词袋降级召回
 * 3. 自动记忆合并
 * 4. 自动去重
 * 5. 自动衰减
 * TODO 长期记忆持久化向量数据库
 */
@Component
public class LongTermMemory {
    /**
     * 长期记忆列表
     */
    private final List<LongTermMemoryItem> items = new CopyOnWriteArrayList<>();

    /**
     * TF词袋词典
     *
     * word -> index
     */
    private final Map<String, Integer> vocabId = new HashMap<>();

    /**
     * 词汇表
     */
    private final List<String> vocab = new ArrayList<>();

    /**
     * 下一个内存ID
     */
    private Long nextId = 0L;

    /**
     * 累计存储次数
     */
    private int storeCount = 0;

    /**
     * 记忆合并配置
     */
    private AppConfig.ConsolidationConfig consolidationCfg;

    public void setConsolidationConfig(AppConfig.ConsolidationConfig cfg) {
        this.consolidationCfg = cfg;
    }

    public List<LongTermMemoryItem> getItems() {
        return new ArrayList<>(items);
    }

    public int size() {
        return items.size();
    }

    /**
     * 将内容存入长期记忆
     * @param content
     * @param importance
     * @param embedding
     * @return
     */
    public boolean store(String content, double importance, List<Float> embedding) {
        if (content == null || content.isBlank()) {
            return false;
        }
        // 1. 去重检测：与已有条目相似度过高时跳过，但更新已有条目的访问时间和重要性
        if (consolidationCfg != null && !items.isEmpty() && embedding != null && !embedding.isEmpty()) {
            for (LongTermMemoryItem item : items) {
                List<Float> existEmbedding = item.getEmbedding();
                if (existEmbedding == null || existEmbedding.size() != embedding.size()) {
                    continue;
                }
                double sim = cosine(embedding, item.getEmbedding());
                if (sim >= consolidationCfg.getDedupThreshold()) {
                    // 保留更高的重要度
                    if (importance > item.getImportance()) {
                        item.setImportance(importance);
                    }
                    // 更新访问时间
                    item.setLastAccessed(LocalDateTime.now());
                    return false;
                }
            }
        }

        // 2. 更新TF词典
        buildVocab(content);

        // 3. 创建记忆
        LocalDateTime now = LocalDateTime.now();
        LongTermMemoryItem item = LongTermMemoryItem.builder()
                // TODO 已经由数据库生成syncLastItemPgId()，建议改成item.setId(null)
                .id(nextId++)
                .content(content)
                .importance(importance)
                .embedding(embedding)
                .createdAt(now)
                .lastAccessed(now)
                .build();
        items.add(item);
        storeCount++;
        return true;
    }

    /**
     * 插入已有 Item（用于从 DB 恢复数据）
     * @param item
     */
    public void storeItem(LongTermMemoryItem item) {
        buildVocab(item.getContent());
        if (item.getId() >= nextId) {
            nextId = item.getId() + 1;
        }
        if (item.getCreatedAt() == null) item.setCreatedAt(LocalDateTime.now());
        if (item.getLastAccessed() == null) item.setLastAccessed(item.getCreatedAt());
        items.add(item);
    }

    /**
     * 将最后一条记忆的 ID 同步为 PG 自增 ID
     * @param pgId
     */
    public void syncLastItemPGID(Long pgId) {
        if (!items.isEmpty() && pgId > 0) {
            items.get(items.size() - 1).setId(pgId);
            if (pgId >= nextId) {
                nextId = pgId + 1;
            }
        }
    }

    /**
     * 检查是否需要触发记忆合并
     * @return
     */
    public boolean needConsolidation() {
        return consolidationCfg != null
                && consolidationCfg.getTriggerInterval() > 0
                && storeCount >= consolidationCfg.getTriggerInterval();
    }

    /**
     * 从长期记忆中召回与 query 最相关的 topK 条
     * 优先使用 embedding 余弦相似度，若无 embedding 则退回 TF，只返回综合得分超过 threshold 的条目，避免注入噪声
     *
     * 综合得分：
     * score = similarity * 0.7 + importance * 0.3
     * @param query
     * @param topK
     * @param queryEmbedding
     * @return
     */
    public List<LongTermMemoryItem> recall(String query, int topK, List<Float> queryEmbedding) {
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        // 综合得分阈值：sim*0.7 + importance*0.3
        final double threshold = 0.4;

        List<ScoredItem> scoredItems = new ArrayList<>();
//        List<double[]> scored = new ArrayList<>();
        for (LongTermMemoryItem item : items) {
            double sim;
            // Embedding召回
            if (CollectionUtils.isNotEmpty(queryEmbedding)
                    && CollectionUtils.isNotEmpty(item.getEmbedding())
                    && item.getEmbedding().size() == queryEmbedding.size()) {
                sim = cosine(queryEmbedding, item.getEmbedding());
            } else {
                // TF降级召回
                // 查询词不进入全局词表，防止查询词无限污染全局词表
//                buildVocab(query);
                List<Float> queryVector = textToVector(query);
                List<Float> itemVector = textToVector(item.getContent());
                int maxLength = Math.max(queryVector.size(), itemVector.size());

                if (queryVector.size() < maxLength) {
                    queryVector.add(0F);
                }

                if (itemVector.size() < maxLength) {
                    itemVector.add(0F);
                }
                sim = cosine(queryVector, itemVector);
            }
            double score = sim * 0.7D + item.getImportance() * 0.3D;
            if (score >= threshold) {
                item.setLastAccessed(LocalDateTime.now());
                scoredItems.add(new ScoredItem(item, score));
            }
        }
        if (scoredItems.isEmpty()) {
            return Collections.emptyList();
        }

        scoredItems.sort(
                Comparator.comparing(
                        ScoredItem::getScore
                ).reversed()
        );
        int limit = Math.min(topK, scoredItems.size());
        List<LongTermMemoryItem> result = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            LongTermMemoryItem item = scoredItems.get(i).getItem();
            item.setScore(scoredItems.get(i).getScore());
            result.add(item);
        }
        return result;
    }

    /**
     * 执行记忆合并：衰减 → 去重+合并 → 过期淘汰
     * 返回合并结果，调用方需根据结果同步 PG
     * @return
     */
    public ConsolidationResult consolidate() {
        ConsolidationResult result = new ConsolidationResult();
        if (consolidationCfg == null || items.size() <= 1) return result;
        storeCount = 0;
        Set<Integer> removed = new HashSet<>();

        // Phase 1: 重要性衰减 — 重要性随时间指数递减
        for (LongTermMemoryItem item : items) {
            double days = ChronoUnit.HOURS.between(item.getCreatedAt(), LocalDateTime.now()) / 24.0;
            item.setImportance(item.getImportance() * Math.pow(consolidationCfg.getDecayRate(), days));
        }

        // Phase 2: 去重 + 合并 — 两两比较相似度
        for (int i = 0; i < items.size(); i++) {
            if (removed.contains(i)) continue;
            for (int j = i + 1; j < items.size(); j++) {
                if (removed.contains(j)) continue;
                double sim = itemSimilarity(items.get(i), items.get(j));
                if (sim >= consolidationCfg.getDedupThreshold()) {
                    // 去重：保留重要性更高的，删除另一个
                    if (items.get(j).getImportance() >= items.get(i).getImportance()) {
                        removed.add(i);
                        result.deduped++;
                        result.deleteFromDB.add(items.get(i).getId());
                    } else {
                        removed.add(j);
                        result.deduped++;
                        result.deleteFromDB.add(items.get(j).getId());
                    }
                } else if (sim >= consolidationCfg.getSimilarityThreshold()) {
                    // 合并：语义相近但非完全重复，合并为一条
                    LongTermMemoryItem merged = mergeItems(items.get(i), items.get(j));
                    items.set(i, merged);
                    removed.add(j);
                    result.merged++;
                    result.deleteFromDB.add(items.get(j).getId());
                    result.updateInDB.add(merged);
                }
            }
        }

        // Phase 3: 过期淘汰 — 低重要性 + 超过 TTL 的条目自动删除
        for (int i = 0; i < items.size(); i++) {
            if (removed.contains(i)) continue;
            double days = ChronoUnit.HOURS.between(items.get(i).getCreatedAt(), LocalDateTime.now()) / 24.0;
            if (consolidationCfg.getTtlDays() > 0
                    && days > consolidationCfg.getTtlDays()
                    && items.get(i).getImportance() < consolidationCfg.getMinImportance()) {
                removed.add(i);
                result.expired++;
                result.deleteFromDB.add(items.get(i).getId());
            }
        }

        // 重建列表和词表
        List<LongTermMemoryItem> newItems = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!removed.contains(i)) newItems.add(items.get(i));
        }
        items.clear();
        items.addAll(newItems);
        rebuildVocab();
        return result;
    }

    private void buildVocab(String text) {
        for (String t : tokenize(text)) {
            if (!vocabId.containsKey(t)) {
                vocabId.put(t, vocab.size());
                vocab.add(t);
            }
        }
    }

    private List<Float> textToVector(String text) {
        List<Float> vector = new ArrayList<>(Collections.nCopies(vocabId.size(), 0F));

        for (String t : tokenize(text)) {
            Integer idx = vocabId.get(t);
            if (idx != null) {
                vector.set(idx, vector.get(idx) + 1);
            }
        }
        return vector;
    }

    /**
     * 重建全局词表（合并/删除后调用）
     */
    private void rebuildVocab() {
        vocabId.clear();
        vocab.clear();
        for (LongTermMemoryItem item : items) {
            buildVocab(item.getContent());
        }
    }

    /**
     * 计算两条记忆之间的相似度
     * @param a
     * @param b
     * @return
     */
    private double itemSimilarity(LongTermMemoryItem a, LongTermMemoryItem b) {
        if (a.getEmbedding() != null && b.getEmbedding() != null
                && !a.getEmbedding().isEmpty() && a.getEmbedding().size() == b.getEmbedding().size()) {
            return cosine(a.getEmbedding(), b.getEmbedding());
        }
        buildVocab(a.getContent());
        buildVocab(b.getContent());
        return cosine(textToVector(a.getContent()), textToVector(b.getContent()));
    }

    /**
     * 合并两条相似记忆，保留重要性更高的作为主体
     * @param a
     * @param b
     * @return
     */
    private LongTermMemoryItem mergeItems(LongTermMemoryItem a, LongTermMemoryItem b) {
        // 以重要性更高的条目为主体
        LongTermMemoryItem base = a.getImportance() >= b.getImportance() ? a : b;
        LongTermMemoryItem other = base == a ? b : a;

        LongTermMemoryItem merged = new LongTermMemoryItem();
        merged.setId(base.getId());
        merged.setImportance(Math.max(base.getImportance(), other.getImportance()));
        merged.setEmbedding(base.getEmbedding());
        merged.setCreatedAt(base.getCreatedAt());
        merged.setLastAccessed(LocalDateTime.now());

        // 内容合并：非子串关系时用分号拼接，否则保留较长的
        if (!base.getContent().contains(other.getContent()) && !other.getContent().contains(base.getContent())) {
            merged.setContent(base.getContent() + "；" + other.getContent());
        } else if (other.getContent().length() > base.getContent().length()) {
            merged.setContent(other.getContent());
        } else {
            merged.setContent(base.getContent());
        }

        // Embedding 按重要性加权平均
        if (base.getEmbedding() != null && other.getEmbedding() != null
                && !base.getEmbedding().isEmpty() && base.getEmbedding().size() == other.getEmbedding().size()) {
            double wA = base.getImportance(), wB = other.getImportance();
            double total = wA + wB;
            if (total > 0) {
                List<Float> mergedEmb = new ArrayList<>();
                for (int i = 0; i < base.getEmbedding().size(); i++) {
                    mergedEmb.add((float) ((base.getEmbedding().get(i) * wA + other.getEmbedding().get(i) * wB) / total));
                }
                merged.setEmbedding(mergedEmb);
            }
        }
        return merged;
    }

    /**
     * 将文本切成词元（中文逐字，英文按单词）
     * @param text
     * @return
     */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) {
                // 中文字符
                if (!word.isEmpty()) {
                    tokens.add(word.toString().toLowerCase());
                    word.setLength(0);
                }
                tokens.add(String.valueOf(c));
            } else if (Character.isLetterOrDigit(c)) {
                // 英文数字
                word.append(c);
            } else {
                // 分隔符
                if (!word.isEmpty()) {
                    tokens.add(word.toString().toLowerCase());
                    word.setLength(0);
                }
            }
        }
        if (!word.isEmpty()) {
            tokens.add(word.toString().toLowerCase());
        }
        return tokens;
    }

    /**
     * 计算两个向量的余弦相似度
     * @param a
     * @param b
     * @return
     */
    public static double cosine(List<Float> a, List<Float> b) {
        if (a.size() != b.size()) {
            return 0D;
        }
        double dot = 0D;
        double normA = 0D;
        double normB = 0D;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        if (normA == 0D || normB == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

//    private static double cosineArr(double[] a, double[] b) {
//        int len = Math.max(a.length, b.length);
//        double dot = 0, na = 0, nb = 0;
//        for (int i = 0; i < len; i++) {
//            double ai = i < a.length ? a[i] : 0;
//            double bi = i < b.length ? b[i] : 0;
//            dot += ai * bi;
//            na += ai * ai;
//            nb += bi * bi;
//        }
//        if (na == 0 || nb == 0) return 0;
//        return dot / (Math.sqrt(na) * Math.sqrt(nb));
//    }

    /**
     * 召回阶段临时得分对象
     */
    @Data
    @AllArgsConstructor
    private static class ScoredItem {

        /**
         * 记忆条目
         */
        private LongTermMemoryItem item;

        /**
         * 综合得分
         */
        private Double score;
    }
}
