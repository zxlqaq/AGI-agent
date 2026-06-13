package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PG 长期记忆
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LongTermMemoryItem {
    private Long id;
    private String content;
    private Double importance;
    private List<Float> embedding;
    private Double score;
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessed;
}
