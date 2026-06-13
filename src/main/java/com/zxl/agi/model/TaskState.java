package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TaskState {

    /**
     * 任务ID
     */
    private String taskId;

    /**
     * 用户问题
     */
    private String query;

    /**
     * running/completed/interrupted
     */
    private String status;

    /**
     * planning/executing/generating/done/interrupted
     */
    private String phase;

    /**
     * 执行步骤
     */
    private List<TaskStep> steps = new ArrayList<>();

    /**
     * 当前步骤
     */
    private Integer currentStep;

    /**
     * 中断位置
     */
    private Integer interruptedAt;

    /**
     * 最终结果
     */
    private String result;
}
