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

    /**
     * Thought / Action / Observation / Final Answer
     */
    private String type;

    /**
     * 步骤内容
     */
    private String content;

    /**
     * 调用工具名称
     */
    private String tool;

    /**
     * 工具参数
     */
    private Map<String, String> params;

    public ReActStep(String type, String content) { this.type = type; this.content = content; }
}
