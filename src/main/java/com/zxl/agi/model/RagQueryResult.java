package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class RagQueryResult {

    /**
     * 最终答案
     */
    private String answer;

    /**
     * 检索结果
     */
    private List<SearchResult> results;
}
