package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallResult {
    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 调用参数
     */
    private Map<String, Object> params;

    /**
     * 工具返回结果
     */
    private String toolResult;
}
