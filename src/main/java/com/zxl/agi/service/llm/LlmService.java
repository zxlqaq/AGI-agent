package com.zxl.agi.service.llm;

import com.zxl.agi.config.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private final AppConfig cfg;
    private final OkHttpClient httpClient;
    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;

    public LlmService(AppConfig cfg,
                      ObjectProvider<ChatModel> chatModelProvider,
                      ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.cfg = cfg;
        this.chatModel = chatModelProvider.getIfAvailable();
        this.embeddingModel = embeddingModelProvider.getIfAvailable();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    // ─────────────────────────────── Chat ────────────────────────────────

    /**
     * Chat 发送对话请求，返回回复文本。
     * @param systemPrompt
     * @param messages
     * @return
     */
    public String chat(String systemPrompt, List<Map<String, String>> messages) {
        if (cfg.isRealLLM()) {
//            if (chatModel != null) {
//                try {
//                    return callSpringAi(systemPrompt, messages);
//                } catch (Exception e) {
//                    log.warn("Spring AI LLM API call failed: {}, falling back to raw HTTP", e.getMessage());
//                }
//            }
            try {
                return callAPI(systemPrompt, messages);
            } catch (Exception e) {
                log.warn("LLM API 调用失败: {}, 回退到 Mock", e.getMessage());
                return mock(messages);
            }
        }
        return mock(messages);
    }

    private String callSpringAi(String systemPrompt, List<Map<String, String>> messages) {
        List<Message> promptMessages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            promptMessages.add(new SystemMessage(systemPrompt));
        }
        for (Map<String, String> message : messages) {
            String role = message.getOrDefault("role", "user");
            String content = message.getOrDefault("content", "");
            if ("assistant".equals(role)) {
                promptMessages.add(new AssistantMessage(content));
            } else if ("system".equals(role)) {
                promptMessages.add(new SystemMessage(content));
            } else {
                promptMessages.add(new UserMessage(content));
            }
        }

        ChatOptions options = ChatOptions.builder()
                .model(cfg.getLlm().getModel())
                .temperature(cfg.getLlm().getTemperature())
                .build();
        ChatResponse response = chatModel.call(new Prompt(promptMessages, options));
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new RuntimeException("Spring AI returned an empty chat response");
        }
        return response.getResult().getOutput().getText();
    }

    private String callAPI(String systemPrompt, List<Map<String, String>> messages) throws Exception {
        List<Map<String, String>> msgs = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            msgs.add(Map.of("role", "system", "content", systemPrompt));
        }
        msgs.addAll(messages);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getLlm().getModel());
        body.put("messages", msgs);
        body.put("temperature", cfg.getLlm().getTemperature());

        String json = mapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(cfg.getLlm().getApiUrl())
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + cfg.getLlm().getApiKey())
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("API 错误 " + response.code() + ": " + responseBody);
            }
            JsonNode root = mapper.readTree(responseBody);
            if (root.has("error") && !root.get("error").isNull()) {
                throw new RuntimeException("API 错误: " + root.get("error").get("message").asText());
            }
            JsonNode choices = root.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("API 返回空结果");
            }
            return choices.get(0).get("message").get("content").asText();
        }
    }

    // ─────────────────────────────── Embedding ────────────────────────────────

    /**
     * 调用 Embedding API 将文本转为向量
     * @param text
     * @return
     */
    public List<Float> embed(String text) {
        if (!cfg.isRealEmbedding()) {
            return null;
        }
        try {
//            if (embeddingModel != null && !isMultimodalEmbedding()) {
//                return callSpringAiEmbedding(text);
//            }
            return callEmbedAPI(text);
        } catch (Exception e) {
            log.warn("Embedding API 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private List<Float> callSpringAiEmbedding(String text) {
        float[] vector = embeddingModel.embed(text);
        if (vector.length == 0) {
            throw new RuntimeException("Spring AI returned an empty embedding response");
        }
        List<Float> embedding = new ArrayList<>(vector.length);
        for (float value : vector) {
            embedding.add(value);
        }
        return embedding;
    }

    private boolean isMultimodalEmbedding() {
        String apiUrl = cfg.getEmbedding().getApiUrl();
        return apiUrl != null && apiUrl.contains("/embeddings/multimodal");
    }

    private List<Float> callEmbedAPI(String text) throws Exception {
        String apiUrl = cfg.getEmbedding().getApiUrl();
        boolean isMultimodal = isMultimodalEmbedding();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getEmbedding().getModel());
        if (isMultimodal) {
            body.put("input", List.of(Map.of("type", "text", "text", text)));
        } else {
            body.put("input", text);
        }

        String json = mapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .addHeader("Authorization", "Bearer " + cfg.getEmbedding().getApiKey())
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("Embedding API 错误 " + response.code());
            }
            JsonNode root = mapper.readTree(responseBody);
            if (root.has("error") && !root.get("error").isNull()) {
                throw new RuntimeException("Embedding API 错误: " + root.get("error").get("message").asText());
            }

            JsonNode embNode;
            if (isMultimodal) {
                embNode = root.get("data").get("embedding");
            } else {
                embNode = root.get("data").get(0).get("embedding");
            }
            if (embNode == null || !embNode.isArray() || embNode.isEmpty()) {
                throw new RuntimeException("Embedding 返回空向量");
            }
            List<Float> embedding = new ArrayList<>();
            for (JsonNode n : embNode) {
                embedding.add(n.floatValue());
            }
            return embedding;
        }
    }

    // ─────────────────────────────── Preference Extraction ────────────────────────────────

    /**
     * 用 LLM 从用户消息中提取偏好键值对
     * @param msg
     * @return
     */
    public Map<String, String> extractPreferences(String msg) {
        if (!cfg.isRealLLM()) {
            return extractRuleBased(msg);
        }
        try {
            String prompt = "从下面这句用户消息中，提取所有用户的个人信息和偏好，输出 JSON 对象（key为中文名称，value为具体值）。\n如果没有任何偏好信息，输出 {}。\n只输出 JSON，不要有其他内容。\n\n消息：" + msg;
            String raw = callAPI("", List.of(Map.of("role", "user", "content", prompt)));
            raw = raw.trim()
                    .replace("```json", "")
                    .replace("```", "")
                    .trim();
            @SuppressWarnings("unchecked")
            Map<String, String> result = mapper.readValue(raw, Map.class);
            return result;
        } catch (Exception e) {
            return extractRuleBased(msg);
        }
    }

    /**
     * 规则兜底：无 API 时使用
     * @param msg
     * @return
     */
    private Map<String, String> extractRuleBased(String msg) {
        Map<String, String> result = new HashMap<>();
        if (msg.contains("我喜欢")) {
            String[] parts = msg.split("喜欢", 2);
            if (parts.length == 2 && !parts[1].trim().isEmpty()) result.put("喜好", parts[1].trim());
        }
        if (msg.contains("我爱")) {
            String[] parts = msg.split("爱", 2);
            if (parts.length == 2 && !parts[1].trim().isEmpty()) result.put("喜好", parts[1].trim());
        }
        if (msg.contains("我叫")) {
            String[] parts = msg.split("叫", 2);
            if (parts.length == 2 && !parts[1].trim().isEmpty()) result.put("姓名", parts[1].trim());
        }
        return result;
    }

    // ─────────────────────────────── Mock ────────────────────────────────

    private String mock(List<Map<String, String>> messages) {
        String userQuery = "";
        for (Map<String, String> m : messages) {
            if ("user".equals(m.get("role"))) userQuery = m.get("content");
        }
        String q = userQuery.toLowerCase();
        if (q.contains("你是谁")) return "我是一个全能 AI 助手，具备知识库、工具调用、推理、记忆和稳定执行能力。";
        if (q.contains("后端工程师")) return "后端工程师负责服务器端逻辑开发：API 设计、数据库、业务逻辑、系统架构、性能优化。常用 Go / Java / Python / MySQL / Redis。";
        return String.format("收到：「%s」——这是模拟 LLM 回复，接入真实 API 后会更智能。", userQuery);
    }
}
