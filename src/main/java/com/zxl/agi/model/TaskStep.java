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

    /**
     * 步骤ID
     */
    private Integer id;

    /**
     * 步骤名称
     */
    private String name;

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 工具参数
     */
    private Map<String, String> params;

    private String status = PENDING;

    /**
     * 执行结果
     */
    private String result;

    /**
     * 错误信息
     */
    private String error;

    /**
     * 重试次数
     */
    private Integer retryCount;

    public TaskStep(int id, String name, String toolName, Map<String, String> params) {
        this.id = id; this.name = name; this.toolName = toolName; this.params = params;
    }
}
