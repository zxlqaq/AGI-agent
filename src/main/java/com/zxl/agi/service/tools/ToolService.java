//package com.zxl.agi.service.tools;
//
//import com.zxl.agi.model.Tool;
//import com.zxl.agi.model.ToolCallResult;
//import com.zxl.agi.model.ToolParam;
//import com.fasterxml.jackson.databind.ObjectMapper;
//import okhttp3.*;
//import org.springframework.stereotype.Service;
//
//import java.time.LocalDateTime;
//import java.time.ZoneId;
//import java.time.ZonedDateTime;
//import java.time.format.DateTimeFormatter;
//import java.util.HashMap;
//import java.util.List;
//import java.util.Map;
//import java.util.concurrent.ConcurrentHashMap;
//
///**
// * 工具注册与执行：内置 get_time / get_weather / search_web，以及 MCP 动态工具。
// * 自动模式下由 {@link #decide} 做规则路由；显式模式由前端指定工具，不经过 decide。
// */
//@Service
//public class ToolService {
//
//    private static final ObjectMapper mapper = new ObjectMapper();
//    private static final OkHttpClient httpClient = new OkHttpClient();
//
//    /** 演示用天气数据，生产环境应替换为真实 API */
//    private static final Map<String, String> WEATHER_DB = Map.of(
//            "北京", "晴天 22°C",
//            "东京", "多云 18°C 湿度65%",
//            "上海", "小雨 20°C",
//            "纽约", "晴天 15°C",
//            "伦敦", "阴天 12°C",
//            "广州", "晴天 28°C",
//            "深圳", "晴天 26°C"
//    );
//
//    /** 注册 README 中列出的三个内置工具 */
//    public Map<String, Tool> getDefaultTools() {
//        Map<String, Tool> tools = new ConcurrentHashMap<>();
//        tools.put("get_time", createGetTimeTool());
//        tools.put("get_weather", createGetWeatherTool());
//        tools.put("search_web", createSearchWebTool());
//        return tools;
//    }
//
//    private Tool createGetTimeTool() {
//        return Tool.builder()
//                .name("get_time")
//                .description("获取当前时间")
//                .parameters(List.of(ToolParam.builder()
//                                .name("timezone")
//                                .type("string")
//                                .description("时区")
//                                .required(false)
//                                .build()))
//                .isMcp(false)
//                .execute(params -> {
//                    ZoneId zoneId = ZoneId.systemDefault();
//                    Object timezone = params.get("timezone");
//                    if (timezone instanceof String tz
//                            && !tz.isBlank()) {
//                        zoneId = ZoneId.of(tz);
//                    }
//                    return LocalDateTime.now(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
//                })
//                .build();
//    }
//
//    private Tool createGetWeatherTool() {
//        return Tool.builder()
//                .name("get_weather")
//                .description("获取城市天气")
//                .parameters(List.of(ToolParam.builder()
//                                .name("city")
//                                .type("string")
//                                .description("城市名称")
//                                .required(true)
//                                .build()))
//                .isMcp(false)
//                .execute(params -> {
//                    String city = String.valueOf(params.get("city"));
//                    return city + "：" + WEATHER_DB.getOrDefault(city, "晴天 20°C（模拟）");
//                })
//                .build();
//    }
//
//    private Tool createSearchWebTool() {
//        return Tool.builder()
//                .name("search_web")
//                .description("搜索互联网")
//                .parameters(List.of(ToolParam.builder()
//                                .name("query")
//                                .type("string")
//                                .description("搜索关键词")
//                                .required(true)
//                                .build()))
//                .isMcp(false)
//                .execute(params -> {
//                    String query = String.valueOf(params.get("query"));
//                    return "关于「" + query + "」的搜索结果（模拟）";
//                })
//                .build();
//    }
//
//    /**
//     * 自动模式下的规则路由：根据用户问句关键词选择工具及参数。
//     * ReAct 流程中由 Planner LLM 选工具时不会调用此方法。
//     *
//     * @return 匹配到的工具调用，无可用工具时返回 null
//     */
//    public ToolCallResult decide(String query, Map<String, Tool> tools) {
//        String q = query.toLowerCase();
//
//        if ((q.contains("几点") || q.contains("时间")) && tools.containsKey("get_time")) {
//            Map<String, Object> params = new HashMap<>();
//            if (q.contains("东京")) params.put("timezone", "Asia/Tokyo");
//            return new ToolCallResult("get_time", params);
//        }
//
//        if (q.contains("天气") && tools.containsKey("get_weather")) {
//            String city = "北京";
//            for (String c : List.of("东京", "北京", "上海", "纽约", "伦敦", "广州", "深圳")) {
//                if (q.contains(c)) { city = c; break; }
//            }
//            return new ToolCallResult("get_weather", Map.of("city", city));
//        }
//
//        if ((q.contains("查") || q.contains("搜索") || q.contains("是什么")) && tools.containsKey("search_web")) {
//            return new ToolCallResult("search_web", Map.of("query", query));
//        }
//
//        // 未命中关键词时兜底：取第一个可用工具，避免自动模式无响应
//        for (String name : tools.keySet()) {
//            return new ToolCallResult(name, Map.of("query", query));
//        }
//        return null;
//    }
//
//    /**
//     * 创建 MCP 工具：将参数 POST 到外部 endpoint，供 {@code POST /api/tools/mcp} 动态注册。
//     */
//    public Tool createMCPTool(String name, String description, String endpoint, List<ToolParam> params) {
//        Tool tool = new Tool(name, description, params, p -> {
//            try {
//                String json = mapper.writeValueAsString(p);
//                RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
//                Request request = new Request.Builder().url(endpoint).post(body).build();
//                try (Response response = httpClient.newCall(request).execute()) {
//                    if (!response.isSuccessful()) {
//                        throw new RuntimeException("MCP 返回错误状态 " + response.code());
//                    }
//                    return response.body() != null ? response.body().string() : "";
//                }
//            } catch (Exception e) {
//                throw new RuntimeException("MCP 请求失败 [" + endpoint + "]: " + e.getMessage());
//            }
//        });
//        tool.setMcp(true);
//        return tool;
//    }
//}
