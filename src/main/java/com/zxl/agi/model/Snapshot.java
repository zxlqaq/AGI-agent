package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Snapshot {
    private TaskState state;
    private String timestamp;
}
