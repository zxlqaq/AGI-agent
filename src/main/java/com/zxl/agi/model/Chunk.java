package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Chunk - 文本切片单元
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class Chunk {
    private Long id;
    private String content;
}
