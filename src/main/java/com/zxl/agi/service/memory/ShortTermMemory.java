package com.zxl.agi.service.memory;

import com.zxl.agi.model.ConversationMessage;
import org.springframework.stereotype.Component;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 短期记忆：近 N 轮对话滑动窗口
 */
@Component
public class ShortTermMemory {

    private final List<ConversationMessage> messages = Collections.synchronizedList(new ArrayList<>());
    private int maxTurns = 5;

    public void setMaxTurns(int maxTurns) { this.maxTurns = maxTurns; }

    /**
     * 追加一条消息，超出窗口时自动丢弃最早记录
     * @param role
     * @param content
     */
    public void add(String role, String content) {
        messages.add(new ConversationMessage(role, content,
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
        int max = maxTurns * 2;
        while (messages.size() > max) {
            messages.remove(0);
        }
    }

    public List<ConversationMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    public int size() { return messages.size(); }
}
