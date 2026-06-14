package com.zxl.agi.service.memory;

import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 用户偏好：从对话中自动提取并持久化的键值对
 * 直接内存：缓存区
 */
@Component
public class PreferenceMemory {

    private final Map<String, String> data = new ConcurrentHashMap<>();

    /**
     * 保存单条偏好
     * @param key
     * @param value
     */
    public void save(String key, String value) {
        if (key != null && !key.isEmpty() && value != null && !value.isEmpty()) {
            data.put(key, value);
        }
    }

    /**
     * 批量保存偏好
     * @param kvs
     */
    public void saveBatch(Map<String, String> kvs) {
        if (kvs != null) {
            kvs.forEach((k, v) -> {
                if (k != null && !k.isEmpty() && v != null && !v.isEmpty()) {
                    data.put(k, v);
                }
            });
        }
    }

    /**
     * 从对话文本中用规则提取偏好
     * @param msg
     * @return
     */
    public String[] extractAndSave(String msg) {
        if (msg.contains("我喜欢")) {
            String[] parts = msg.split("喜欢", 2);
            if (parts.length == 2 && !parts[1].trim().isEmpty()) {
                String key = "喜好", value = parts[1].trim();
                data.put(key, value);
                return new String[]{key, value};
            }
        }
        if (msg.contains("我爱")) {
            String[] parts = msg.split("爱", 2);
            if (parts.length == 2 && !parts[1].trim().isEmpty()) {
                String key = "喜好", value = parts[1].trim();
                data.put(key, value);
                return new String[]{key, value};
            }
        }
        if (msg.contains("我叫")) {
            String[] parts = msg.split("叫", 2);
            if (parts.length == 2 && !parts[1].trim().isEmpty()) {
                String key = "姓名", value = parts[1].trim();
                data.put(key, value);
                return new String[]{key, value};
            }
        }
        return null;
    }

    /**
     * 将偏好数据格式化为给 LLM 的上下文字符串
     * @return
     */
    public String buildContext() {
        if (data.isEmpty()) return "";
        String items = data.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));
        return "【用户偏好】\n" + items;
    }

    public Map<String, String> getData() { return new LinkedHashMap<>(data); }
}
