package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Data
public class Tool {
    private String name;
    private String description;
    private List<ToolParam> parameters;
    private boolean mcp;
    private transient Function<Map<String, Object>, String> execute;

    public Tool(String name, String description, List<ToolParam> parameters, Function<Map<String, Object>, String> execute) {
        this.name = name; this.description = description; this.parameters = parameters; this.execute = execute;
    }
}
