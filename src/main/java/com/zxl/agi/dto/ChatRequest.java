package com.zxl.agi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * ChatRequest - chat请求体
 */
@Data
public class ChatRequest {
    private String message;

    /**
     * 是否使用 RAG 知识库
     */
    @JsonProperty("use_rag")
    private boolean useRag;

    /**
     * 用户明确选中的工具列表
     */
    @JsonProperty("selected_tools")
    private List<String> selectedTools;

    /**
     * true 时以 SelectedTools/UseRAG 为准，false 时自动路由
     */
    private boolean explicit;
}
