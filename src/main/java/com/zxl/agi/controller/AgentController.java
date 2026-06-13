package com.zxl.agi.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zxl.agi.config.AppConfig;
import com.zxl.agi.dto.ChatRequest;
import com.zxl.agi.dto.ChatResponse;
import com.zxl.agi.dto.RegisterMCPToolReq;
import com.zxl.agi.infrastructure.InfrastructureService;
import com.zxl.agi.model.Chunk;
import com.zxl.agi.model.Snapshot;
import com.zxl.agi.model.Tool;
import com.zxl.agi.service.agent.UnifiedAgentService;
import com.zxl.agi.service.rag.RagService;
import com.zxl.agi.service.tools.MCPToolFactory;
import com.zxl.agi.service.tools.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * REST API Controller - corresponds to Go internal/handler/handler.go
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AgentController {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final UnifiedAgentService agent;
    private final InfrastructureService infra;
    private final AppConfig cfg;
    private final MCPToolFactory mcpToolFactory;
    private final ToolRegistry toolRegistry;

    /**
     * POST /api/chat - 统一对话入口
     */
    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest req) {
        return agent.processWithOptions(req.getMessage(), req);
    }

    /**
     * POST /api/chat/stream - SSE streaming conversation
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(60000L);
        executor.submit(() -> {
            try {
                emitter.send(SseEmitter.event().name("start")
                        .data(Map.of("message", req.getMessage())));
                ChatResponse resp = agent.processWithOptions(req.getMessage(), req);
                emitter.send(SseEmitter.event().name("done").data(resp));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * POST /api/chat/cancel - Cancel current task
     */
    @PostMapping("/chat/cancel")
    public Map<String, Object> chatCancel() {
        agent.cancel();
        return Map.of("ok", true, "message", "已发送取消信号");
    }

    /**
     * POST /api/upload - 上传文档到 RAG 知识库
     */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestBody Map<String, String> req) {
        String content = req.get("content");
        if (content == null || content.isEmpty()) {
            return Map.of("error", "content is required");
        }
        Map.Entry<Integer, String> result = agent.getRagService().ingest(content);
        List<Chunk> chunks = agent.getRagService().getChunks();
        return Map.of(
                "chunk_count", result.getKey(),
                "doc_hash", result.getValue(),
                "chunks", chunks
        );
    }

    /**
     * POST /api/docs/delete - Delete RAG document by hash
     */
    @PostMapping("/docs/delete")
    public Map<String, Object> docsDelete(@RequestBody Map<String, String> req) {
        String docHash = req.get("doc_hash");
        if (docHash == null || docHash.isEmpty()) {
            return Map.of("error", "doc_hash is required");
        }
        agent.getRagService().delete(docHash);
        return Map.of("ok", true, "doc_hash", docHash);
    }

    /**
     * GET /api/memory - 查看三层记忆状态
     */
    @GetMapping("/memory")
    public Map<String, Object> memory() {
        return Map.of(
                "short_term", agent.getShortTermMemory().getMessages(),
                "long_term", agent.getLongTermMemory().getItems(),
                "preference", agent.getPreferences().getData()
        );
    }

    /**
     * GET /api/tools - 列出所有可用工具
     */
    @GetMapping("/tools")
    public List<Map<String, Object>> toolsList() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Tool t : agent.getTools().values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", t.getName());
            info.put("description", t.getDescription());
            if (t.getIsMcp()) {
                info.put("is_mcp", true);
            }
            if (t.getParameters() != null && !t.getParameters().isEmpty()) {
                info.put("params", t.getParameters());
            }
            list.add(info);
        }
        return list;
    }

    /**
     * POST /api/tools/mcp - 动态注册一个 MCP 工具
     */
    @PostMapping("/tools/mcp")
    public Map<String, Object> registerMCPTool(@RequestBody RegisterMCPToolReq req) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }

        if (req.getEndpoint() == null || req.getEndpoint().isBlank()) {
            throw new IllegalArgumentException("endpoint is required");
        }

        Tool tool = mcpToolFactory.newMcpTool(req.getName(), req.getDescription(), req.getEndpoint(), req.getParams());
        // 注册mcp工具
        toolRegistry.registerTool(tool);
        agent.registerTool(tool);
        return Map.of("ok", true, "name", req.getName());
    }

    /**
     * GET /api/snapshots - 列出任务执行快照摘要
     */
    @GetMapping("/snapshots")
    public List<Map<String, Object>> snapshots() {
        List<Snapshot> snaps = agent.getSnapshots();
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < snaps.size(); i++) {
            Snapshot snap = snaps.get(i);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("index", i);
            info.put("timestamp", snap.getTimestamp());
            info.put("steps", snap.getState().getSteps() != null ? snap.getState().getSteps().size() : 0);
            result.add(info);
        }
        return result;
    }

    /**
     * GET /api/status - 系统状态与配置摘要
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        RagService ragService = agent.getRagService();
        List<Chunk> chunks = ragService.getChunks();
        List<Map<String, Object>> chunkPreviews = new ArrayList<>();
        for (Chunk c : chunks) {
            String preview = c.getContent();
            if (preview.length() > 60) {
                preview = preview.substring(0, 60) + "...";
            }
            chunkPreviews.add(Map.of("id", c.getId(), "content", preview));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rag_loaded", ragService.isLoaded());
        result.put("rag_mode", ragService.getMode());
        result.put("rag_chunks", chunkPreviews);
        result.put("short_term_count", agent.getShortTermMemory().size());
        result.put("long_term_count", agent.getLongTermMemory().size());
        result.put("preferences", agent.getPreferences().getData());
        result.put("tools_count", agent.getTools().size());
        result.put("llm_model", cfg.getLlm().getModel());
        result.put("embedding_model", cfg.getEmbedding().getModel());
        result.put("is_mock", !cfg.isRealLLM());
        result.put("infrastructure", infra.getStatus());
        return result;
    }
}
