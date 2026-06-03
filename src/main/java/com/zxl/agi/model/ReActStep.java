package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Map;

@Data
@AllArgsConstructor
public class ReActStep {
    public static final String THOUGHT = "Thought";
    public static final String ACTION = "Action";
    public static final String OBSERVATION = "Observation";
    public static final String FINAL_ANSWER = "Final Answer";

    private String type;
    private String content;
    private String tool;
    private Map<String, String> params;

    public ReActStep(String type, String content) { this.type = type; this.content = content; }
}
