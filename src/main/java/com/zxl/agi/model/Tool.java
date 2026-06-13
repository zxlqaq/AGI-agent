package com.zxl.agi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zxl.agi.service.tools.ToolExecutor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Tool {
    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具描述
     */
    private String description;

    /**
     * 参数定义
     */
    private List<ToolParam> parameters;

    /**
     * 是否MCP工具
     */
    private Boolean isMcp;

    /**
     * 工具执行器
     */
    @JsonIgnore
    private ToolExecutor execute;
}
