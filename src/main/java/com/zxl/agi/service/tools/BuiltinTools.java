package com.zxl.agi.service.tools;

import com.zxl.agi.model.Tool;
import com.zxl.agi.model.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class BuiltinTools {

    /** 演示用天气数据，生产环境应替换为真实 API */
    private static final Map<String, String> WEATHER_DB = Map.of(
            "北京", "晴天 22°C",
            "东京", "多云 18°C 湿度65%",
            "上海", "小雨 20°C",
            "纽约", "晴天 15°C",
            "伦敦", "阴天 12°C",
            "广州", "晴天 28°C",
            "深圳", "晴天 26°C"
    );

    /**
     * 获取当前时间
     */
    public Tool getTime() {

        return Tool.builder()
                .name("get_time")
                .description("获取当前时间")
                .parameters(List.of(ToolParam.builder()
                                .name("timezone")
                                .type("string")
                                .description("时区")
                                .required(false)
                                .build()))
                .isMcp(false)
                .execute(params -> {
                    ZoneId zoneId = ZoneId.systemDefault();
                    Object timezone = params.get("timezone");
                    if (timezone instanceof String tz
                            && !tz.isBlank()) {
                        zoneId = ZoneId.of(tz);
                    }
                    return LocalDateTime.now(zoneId)
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));})
                .build();
    }

    /**
     * 获取天气
     */
    public Tool getWeather() {
        return Tool.builder()
                .name("get_weather")
                .description("获取城市天气")
                .parameters(List.of(ToolParam.builder()
                                .name("city")
                                .type("string")
                                .description("城市名称")
                                .required(true)
                                .build()))
                .isMcp(false)
                .execute(params -> {
                    String city = String.valueOf(params.get("city"));
                    return city + "：" + WEATHER_DB.getOrDefault(city, "晴天 20°C（模拟）");})
                .build();
    }

    /**
     * 搜索互联网
     */
    public Tool searchWeb() {
        return Tool.builder()
                .name("search_web")
                .description("搜索互联网")
                .parameters(List.of(ToolParam.builder()
                                .name("query")
                                .type("string")
                                .description("搜索关键词")
                                .required(true)
                                .build()))
                .isMcp(false)
                .execute(params -> {
                    String query = String.valueOf(params.get("query"));
                    return "关于「" + query + "」的搜索结果（模拟）";})
                .build();
    }
}
