package com.zxl.agi.service.tools;

import com.zxl.agi.model.Tool;
import com.zxl.agi.model.ToolCallResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolDecider {

    /**
     * 基于规则推断工具
     */
    public ToolCallResult decide(String query, Map<String, Tool> tools) {
        String q = query.toLowerCase();
        if (q.contains("几点") || q.contains("时间")) {
            if (tools.containsKey("get_time")) {
                Map<String, Object> params = new HashMap<>();
                if (q.contains("东京")) {
                    params.put("timezone", "Asia/Tokyo");
                }

                return ToolCallResult.builder()
                        .toolName("get_time")
                        .params(params)
                        .build();
            }
        }

        if (q.contains("天气")) {
            if (tools.containsKey("get_weather")) {
                String city = "北京";
                for (String c : List.of(
                        "东京",
                        "北京",
                        "上海",
                        "纽约",
                        "伦敦",
                        "广州",
                        "深圳")) {
                    if (q.contains(c)) {
                        city = c;
                        break;
                    }
                }

                return ToolCallResult.builder()
                        .toolName("get_weather")
                        .params(Map.of("city", city))
                        .build();
            }
        }

        if (q.contains("查") || q.contains("搜索") || q.contains("是什么")) {
            if (tools.containsKey(
                    "search_web")) {

                return ToolCallResult.builder()
                        .toolName("search_web")
                        .params(Map.of("query", query))
                        .build();
            }
        }

        return null;
    }
}
