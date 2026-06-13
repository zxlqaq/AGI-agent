package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Snapshot {

    /**
     * 任务状态
     */
    private TaskState state;

    /**
     * 快照时间
     */
    private String timestamp;
}
