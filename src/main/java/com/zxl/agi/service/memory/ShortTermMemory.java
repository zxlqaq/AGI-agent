package com.zxl.agi.service.memory;

import com.zxl.agi.config.AppConfig;
import com.zxl.agi.model.ConversationMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 短期记忆：近 N 轮对话滑动窗口
 * TODO 后期改用redis
 */
@Component
@RequiredArgsConstructor
public class ShortTermMemory {
    private final AppConfig appConfig;

    private final List<ConversationMessage> messages = Collections.synchronizedList(new ArrayList<>());

    /**
     * 追加一条消息，超出窗口时自动丢弃最早记录
     * @param role
     * @param content
     */
    public void add(String role, String content) {
        messages.add(new ConversationMessage(role, content,
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
        int max = appConfig.getMemory().getShortTermMaxTurns() * 2;
        while (messages.size() > max) {
            messages.remove(0);
        }
    }

    public List<ConversationMessage> getMessages() {
        return new ArrayList<>(messages);
    }

    public int size() { return messages.size(); }
}
