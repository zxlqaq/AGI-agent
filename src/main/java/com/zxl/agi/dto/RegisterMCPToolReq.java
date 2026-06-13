package com.zxl.agi.dto;

import com.zxl.agi.model.ToolParam;
import lombok.Data;

import java.util.List;

@Data
public class RegisterMCPToolReq {

    /**
     * 工具名称
     */
    private String name;

    /**
     * 工具描述
     */
    private String description;

    /**
     * MCP服务地址
     */
    private String endpoint;

    /**
     * 参数定义
     */
    private List<ToolParam> params;
}
