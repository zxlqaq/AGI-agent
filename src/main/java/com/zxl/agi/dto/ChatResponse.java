package com.zxl.agi.dto;

import com.zxl.agi.model.*;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * AgentResponse - UnifiedAgent.Process 的输出
 * 携带本次请求的全部上下文
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatResponse {

    /**
     * 用户问题
     */
    private String query;

    /**
     * AI回答
     */
    private String answer;

    /**
     * chat/tool/rag/memory/react
     */
    private String mode;

    /**
     * ReAct步骤
     */
    private List<ReActStep> steps;

    /**
     * 工具调用记录
     */
    @JsonProperty("tool_call")
    private ToolCallResult toolCall;

    /**
     * RAG检索结果
     */
    @JsonProperty("search_results")
    private List<SearchResult> searchResults;

    /**
     * 当前任务
     */
    private TaskState task;

    /**
     * 提取出的偏好信息
     */
    @JsonProperty("extracted_info")
    private String extractedInfo;

    /**
     * STM数量
     */
    @JsonProperty("short_term_count")
    private int shortTermCount;

    /**
     * LTM数量
     */
    @JsonProperty("long_term_count")
    private int longTermCount;

    /**
     * 用户偏好
     */
    private Map<String, String> preferences;

    /**
     * 是否中断
     */
    private Boolean interrupted;

//    @Data
//    public static class SearchResultDto {
//        private Chunk chunk;
//        private double similarity;
//        public SearchResultDto() {}
//        public SearchResultDto(Chunk chunk, double similarity) { this.chunk = chunk; this.similarity = similarity; }
//        public Chunk getChunk() { return chunk; }
//        public void setChunk(Chunk chunk) { this.chunk = chunk; }
//        public double getSimilarity() { return similarity; }
//        public void setSimilarity(double similarity) { this.similarity = similarity; }
//    }
}
