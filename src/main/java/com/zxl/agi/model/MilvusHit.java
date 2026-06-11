package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Milvus 向量检索结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MilvusHit {

    /**
     * PG主键ID
     */
    private Long id;

    /**
     * 向量距离
     */
    private Float distance;
}
