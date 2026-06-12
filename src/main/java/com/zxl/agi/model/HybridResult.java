package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * HybridResult 是混合检索的单条结果
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class HybridResult {

    private Chunk chunk;

    private Double score;

    /**
     *  hybrid | semantic | keyword | unavailable
     */
    private String source;
}
