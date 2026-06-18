package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class RagQueryResult {

    /**
     * 是否命中知识库
     */
    private boolean hit;

    /**
     * 最高召回分数
     *
     * 用于：
     * 1. 判断知识库是否可靠
     * 2. 后续做Fallback
     * 3. 统计命中质量
     */
    private Double maxScore;

    /**
     * 最终答案
     */
    private String answer;

    /**
     * 检索结果
     */
    private List<SearchResult> results;
}
