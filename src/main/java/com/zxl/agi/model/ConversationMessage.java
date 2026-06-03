package com.zxl.agi.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * ConversationMessage - 单条对话记录
 */
@Data
@AllArgsConstructor
public class ConversationMessage {
    private String role;
    private String content;
    private String timestamp;
}
