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
    private String query;
    private String answer;
    private String mode;
    private List<ReActStep> steps;
    @JsonProperty("tool_call")
    private ToolCallResult toolCall;
    @JsonProperty("search_results")
    private List<SearchResultDto> searchResults;
    private TaskState task;
    @JsonProperty("extracted_info")
    private String extractedInfo;
    @JsonProperty("short_term_count")
    private int shortTermCount;
    @JsonProperty("long_term_count")
    private int longTermCount;
    private Map<String, String> preferences;
    private Boolean interrupted;

    @Data
    public static class SearchResultDto {
        private Chunk chunk;
        private double similarity;
        public SearchResultDto() {}
        public SearchResultDto(Chunk chunk, double similarity) { this.chunk = chunk; this.similarity = similarity; }
        public Chunk getChunk() { return chunk; }
        public void setChunk(Chunk chunk) { this.chunk = chunk; }
        public double getSimilarity() { return similarity; }
        public void setSimilarity(double similarity) { this.similarity = similarity; }
    }
}
