package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ToolParam {
    /**
     * 参数名
     */
    private String name;

    /**
     * 参数类型
     */
    private String type;

    /**
     * 参数描述
     */
    private String description;

    /**
     * 是否必填
     */
    private Boolean required;
}
