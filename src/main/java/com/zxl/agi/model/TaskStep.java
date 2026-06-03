package com.zxl.agi.model;

import lombok.Data;

import java.util.Map;

@Data
public class TaskStep {
    public static final String PENDING = "pending";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String INTERRUPTED = "interrupted";

    private int id;
    private String name;
    private String toolName;
    private Map<String, String> params;
    private String status = PENDING;
    private String result;
    private String error;
    private int retryCount;

    public TaskStep(int id, String name, String toolName, Map<String, String> params) {
        this.id = id; this.name = name; this.toolName = toolName; this.params = params;
    }
}
