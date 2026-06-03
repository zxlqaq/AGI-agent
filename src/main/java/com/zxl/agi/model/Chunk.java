package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Chunk - 文本切片单元
 */
@Data
@AllArgsConstructor
public class Chunk {
    private int id;
    private String content;
}
