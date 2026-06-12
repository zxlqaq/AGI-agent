package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MemoryItem {
    private int id;
    private String content;
    private double importance;
    private List<Float> embedding;
    private double score;
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessed;

    public MemoryItem(int id, String content, double importance, List<Float> embedding) {
        this.id = id; this.content = content; this.importance = importance; this.embedding = embedding;
        this.createdAt = LocalDateTime.now(); this.lastAccessed = LocalDateTime.now();
    }
}
