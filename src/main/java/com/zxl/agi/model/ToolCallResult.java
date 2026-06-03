package com.zxl.agi.model;

import lombok.Data;

import java.util.Map;

@Data
public class ToolCallResult {
    private String toolName;
    private Map<String, Object> params;
    private String toolResult;

    public ToolCallResult(String toolName, Map<String, Object> params) {
        this.toolName = toolName; this.params = params;
    }
}
