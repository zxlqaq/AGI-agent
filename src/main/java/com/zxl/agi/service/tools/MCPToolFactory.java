package com.zxl.agi.service.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.agi.model.Tool;
import com.zxl.agi.model.ToolParam;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
public class MCPToolFactory {
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 创建MCP工具
     */
    public Tool newMcpTool(String name, String description,
            String endpoint, List<ToolParam> ToolParams) {

        return Tool.builder()
                .name(name)
                .description(description)
                .parameters(ToolParams)
                .isMcp(true)
                .execute(request -> {
                    RestTemplate restTemplate = new RestTemplate();
                    String body = objectMapper.writeValueAsString(request);

                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    HttpEntity<String> entity = new HttpEntity<>(body, headers);

                    ResponseEntity<String> response = restTemplate.exchange(
                            endpoint,
                            HttpMethod.POST,
                            entity,
                            String.class
                            );

                    return response.getBody();
                })
                .build();
    }
}
