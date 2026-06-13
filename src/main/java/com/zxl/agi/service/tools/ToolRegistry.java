package com.zxl.agi.service.tools;

import com.zxl.agi.model.Tool;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class ToolRegistry {
    private final BuiltinTools builtinTools;

    /**
     * 全局工具注册表
     */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 初始化内置工具
     */
    @PostConstruct
    public void init() {
        registerTool(builtinTools.getTime());
        registerTool(builtinTools.getWeather());
        registerTool(builtinTools.searchWeb());
    }

    /**
     * 注册工具
     */
    public void registerTool(Tool tool) {
        if (tool == null) {
            return;
        }
        tools.put(tool.getName(), tool);
    }

    /**
     * 返回所有工具
     */
    public Map<String, Tool> getAllTools() {
        return tools;
    }

    /**
     * 获取工具
     */
    public Tool getTool(String toolName) {
        return tools.get(toolName);
    }

    public Map<String, Tool> getTools(List<String> toolNames) {
        if (CollectionUtils.isEmpty(toolNames)) {
            return Collections.emptyMap();
        }
        Map<String, Tool> result = new LinkedHashMap<>();
        for (String toolName : toolNames) {
            Tool tool = tools.get(toolName);
            if (tool != null) {
                result.put(toolName, tool);
            }
        }

        return result;
    }
}
