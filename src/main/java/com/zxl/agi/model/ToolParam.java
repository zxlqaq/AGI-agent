package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ToolParam {
    private String name;
    private String type;
    private String description;
    private boolean required;
}
