package com.zxl.agi.model;

import lombok.Data;

import java.util.List;

@Data
public class TaskState {
    private String taskId;
    private String query;
    private String status;
    private String phase;
    private List<TaskStep> steps;
    private int currentStep;
    private int interruptedAt;
    private String result;
}
