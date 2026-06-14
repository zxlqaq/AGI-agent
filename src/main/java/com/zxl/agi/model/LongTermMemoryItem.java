package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PG 长期记忆
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LongTermMemoryItem {

    /**
     * PostgreSQL主键ID
     */
    private Long id;

    /**
     * 记忆内容
     */
    private String content;

    /**
     * 重要度
     *
     * 范围：0~1
     * 越大表示越重要
     */
    private Double importance;

    /**
     * 向量数据
     *
     * 仅用于语义召回
     */
    private List<Float> embedding;

    /**
     * Milvus召回相似度
     *
     * 不持久化
     */
    private Float similarity;

    /**
     * 召回阶段计算出的综合得分
     *
     * 不持久化
     */
    private Double score;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 最后访问时间
     */
    private LocalDateTime lastAccessed;
}
