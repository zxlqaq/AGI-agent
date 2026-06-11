package com.zxl.agi.service.agent;

import com.alibaba.fastjson.JSON;
import com.zxl.agi.config.AppConfig;
import com.zxl.agi.dto.ChatRequest;
import com.zxl.agi.dto.ChatResponse;
import com.zxl.agi.infrastructure.InfrastructureService;
import com.zxl.agi.model.*;
import com.zxl.agi.service.llm.LlmService;
import com.zxl.agi.service.memory.LongTermMemory;
import com.zxl.agi.service.memory.PreferenceMemory;
import com.zxl.agi.service.memory.ShortTermMemory;
import com.zxl.agi.service.rag.RagService;
import com.zxl.agi.service.tools.ToolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UnifiedAgent - 整合全部 6 个阶段能力的核心调度器
 *
 * 路由策略（按优先级）：
 * 1. ReAct + Harness — 复合查询（含 2+ 子需求，需多步推理）
 * 2. Tool Agent      — 单一工具触发（时间 / 天气 / 搜索）
 * 3. RAG             — 知识库已加载且无工具触发
 * 4. Memory          — 存在用户偏好或长期记忆可利用
 * 5. Chat            — 直接与 LLM 对话
 */
@Service
public class UnifiedAgentService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedAgentService.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient();

    private final AppConfig cfg;
    private final LlmService llm;
    private final RagService rag;
    private final ToolService toolService;
    private final ShortTermMemory stm;
    private final LongTermMemory ltm;
    private final PreferenceMemory pref;
    private final InfrastructureService infra;

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final List<Snapshot> snapshots = Collections.synchronizedList(new ArrayList<>());
    private volatile TaskState currentTask;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public UnifiedAgentService(AppConfig cfg, LlmService llm, RagService rag, ToolService toolService,
                               ShortTermMemory stm, LongTermMemory ltm, PreferenceMemory pref,
                               InfrastructureService infra) {
        this.cfg = cfg;
        this.llm = llm;
        this.rag = rag;
        this.toolService = toolService;
        this.stm = stm;
        this.ltm = ltm;
        this.pref = pref;
        this.infra = infra;
    }

    @PostConstruct
    public void init() {
        // Configure STM
        stm.setMaxTurns(cfg.getMemory().getShortTermMaxTurns());

        // Configure LTM consolidation
        ltm.setConsolidationConfig(cfg.getMemory().getConsolidation());

        // Register default tools
        tools.putAll(toolService.getDefaultTools());

        // Inject RAG callbacks
        rag.setGenerateFn((systemPrompt, userMsg) -> {
            String memPrefix = buildMemorySystemPrefix();
            String fullSystem = systemPrompt;
            if (!memPrefix.isEmpty()) {
                fullSystem = memPrefix + "\n\n" + systemPrompt + "\n结合用户偏好和记忆，用用户熟悉的方式回答。";
            }
            return llm.chat(fullSystem, List.of(Map.of("role", "user", "content", userMsg)));
        });
        rag.setEmbedFn(text -> llm.embed(text));

        // Init RAG infra
        infra.initRAGInfra(cfg.getRag().getRagMilvusDim());

        // 初始化 RAG 基础设施（Milvus collection + ES 索引）
        infra.initRagInfra(cfg.getRag().getRagMilvusDim());

        // Register rag_search tool
        tools.put("rag_search", new Tool("rag_search", "从私人黑洞（个人知识库）中检索相关文档内容",
                List.of(new ToolParam("query", "string", "检索关键词或问题", true)),
                params -> {
                    String q = params.get("query") != null ? params.get("query").toString() : "相关内容";
                    if (!rag.isLoaded()) throw new RuntimeException("知识库为空，请先在「私人黑洞」上传文档");
                    return rag.query(q).answer;
                }));

        // Override search_web with Tavily + LLM fallback
        tools.put("search_web", new Tool("search_web", "搜索互联网获取最新信息",
                List.of(new ToolParam("query", "string", "搜索关键词", true)),
                params -> {
                    String q = params.get("query") != null ? params.get("query").toString() : "";
                    if (q.isEmpty()) throw new RuntimeException("搜索关键词不能为空");
                    // Try Tavily first
                    if (cfg.getSearch().getApiKey() != null && !cfg.getSearch().getApiKey().isEmpty()) {
                        try {
                            return tavilySearch(q, cfg.getSearch().getApiKey(), cfg.getSearch().getApiUrl());
                        } catch (Exception ignored) {}
                    }
                    // Fallback to LLM
                    return llm.chat("你是一个知识丰富的搜索引擎助手。请基于你的知识，对用户的搜索问题给出准确、详细的回答。直接给出答案，不要说「我不知道」或「我无法搜索」。",
                            List.of(Map.of("role", "user", "content", "搜索：" + q)));
                }));

        // Restore from DB
        restoreFromDB();
        restoreRAGFromDB();

        log.info("UnifiedAgent 初始化完成: {} 个工具, STM={}, LTM={}, Prefs={}",
                tools.size(), stm.size(), ltm.size(), pref.getData().size());
    }

    // ===== Public API =====

    public ChatResponse process(String query) {
        return processWithOptions(query, new ChatRequest());
    }

    public ChatResponse processWithOptions(String query, ChatRequest req) {
        cancelled.set(false);
        ChatResponse resp = new ChatResponse();
        resp.setQuery(query);
        resp.setMode("chat");

        // 更新短期记忆
        stm.add("user", query);

        // 持久化用户消息到 PG
        infra.saveChatHistory("user", query);

        // 偏好提取：优先 LLM，降级规则
        new Thread(() -> {
            Map<String, String> kvs = llm.extractPreferences(query);
            if (kvs != null && !kvs.isEmpty()) {
                pref.saveBatch(kvs);
                for (Map.Entry<String, String> e : kvs.entrySet()) {
                    infra.savePreference("default", e.getKey(), e.getValue());
                    String content = "用户" + e.getKey() + ": " + e.getValue();
                    List<Double> emb = llm.embed(content);
                    if (ltm.store(content, 0.8, emb)) {
                        String embJson = "null";
                        try { if (emb != null) embJson = mapper.writeValueAsString(emb); } catch (Exception ignored) {}
                        int pgId = infra.saveLongTermItem(content, 0.8, embJson);
                        ltm.syncLastItemPGID(pgId);
                    }
                }
            }
        }).start();

        // 同步规则提取（用于立即展示 ExtractedInfo）
        String[] extracted = pref.extractAndSave(query);
        if (extracted != null) {
            resp.setExtractedInfo("已记住：" + extracted[0] + " = " + extracted[1]);
        }

        // 构建所有模式共享的记忆增强层
        String memPrefix = buildMemorySystemPrefixWithCtx(query);
        List<Map<String, String>> histMsgs = buildHistoryMessages(query);

        // 检查 context 是否已取消
        if (cancelled.get()) {
            resp.setInterrupted(true);
            resp.setAnswer("[已中断] 请求在开始前被取消");
            return resp;
        }

        // 路由（记忆已注入，不再是独立分支）
        if (req.isExplicit()) {
            if (req.getSelectedTools() != null && !req.getSelectedTools().isEmpty()) {
                Map<String, Tool> filtered = filterTools(req.getSelectedTools());
                // 只要工具集非空就走 ReAct，保证每次工具调用都有完整推理轨迹
                if (!filtered.isEmpty()) {
                    resp.setMode("react");
                    runReActWithTools(resp, query, filtered, memPrefix, histMsgs);
                } else {
                    resp.setMode("tool");
                    runToolFromSet(resp, query, tools, memPrefix, histMsgs);
                }
            } else if (req.isUseRag() && rag.isLoaded()) {
                resp.setMode("rag");
                RagService.QueryResult qr = rag.query(query);
                resp.setAnswer(qr.answer);
                resp.setSearchResults(toSearchResults(qr.results));
            } else {
                resp.setMode("chat");
                String sp = buildSystemPrompt(memPrefix, "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。");
                resp.setAnswer(llm.chat(sp, histMsgs));
            }
        } else {
            // Auto routing
            if (needReAct(query)) {
                resp.setMode("react");
                runReActWithTools(resp, query, tools, memPrefix, histMsgs);
            } else if (needTool(query)) {
                resp.setMode("tool");
                runToolFromSet(resp, query, tools, memPrefix, histMsgs);
            } else if (needRAG(query)) {
                resp.setMode("rag");
                RagService.QueryResult qr = rag.query(query);
                resp.setAnswer(qr.answer);
                resp.setSearchResults(toSearchResults(qr.results));
            } else {
                resp.setMode("chat");
                String sp = buildSystemPrompt(memPrefix, "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。");
                resp.setAnswer(llm.chat(sp, histMsgs));
            }
        }

        // 检查是否被中断
        if (cancelled.get()) resp.setInterrupted(true);

        // 从llm回答中提取可记忆信息
        stm.add("assistant", resp.getAnswer());
        infra.saveChatHistory("assistant", resp.getAnswer());

        // 从llm回答中提取可记忆信息
        final String answer = resp.getAnswer();
        new Thread(() -> extractMemoryFromReply(answer)).start();

        // 异步触发记忆合并（去重+合并+衰减+过期）
        new Thread(() -> {
            if (ltm.needConsolidation()) {
                LongTermMemory.ConsolidationResult result = ltm.consolidate();
                syncConsolidationToDB(result);
            }
        }).start();

        // Publish event
        try {
            String eventData = mapper.writeValueAsString(Map.of("query", query, "mode", resp.getMode()));
            infra.publishEvent("agent.chat", eventData);
        } catch (Exception ignored) {}

        resp.setShortTermCount(stm.size());
        resp.setLongTermCount(ltm.size());
        resp.setPreferences(pref.getData());
        return resp;
    }

    public void cancel() { cancelled.set(true); }

    public void registerTool(Tool tool) { tools.put(tool.getName(), tool); }

    // ===== Accessors =====
    public Map<String, Tool> getTools() { return tools; }
    public ShortTermMemory getShortTermMemory() { return stm; }
    public LongTermMemory getLongTermMemory() { return ltm; }
    public PreferenceMemory getPreferences() { return pref; }
    public List<Snapshot> getSnapshots() { return new ArrayList<>(snapshots); }
    public RagService getRagService() { return rag; }

    /**
     * MCP工具路由判断
     * @param query
     * @return
     */
    private boolean needTool(String query) {
        String q = query.toLowerCase();
        return q.contains("几点") || q.contains("时间") || q.contains("天气")
                || q.contains("查") || q.contains("搜索") || q.contains("是什么");
    }

    private boolean needRAG(String query) {
        return rag.isLoaded() && !needTool(query) && !needReAct(query);
    }

    /**
     * 查询涉及多个个子需求时触发多步推理
     * @param query
     * @return
     */
    private boolean needReAct(String query) {
        String q = query.toLowerCase();
        int count = 0;
        if (q.contains("时间") || q.contains("几点")) {
            count++;
        }
        if (q.contains("天气")) {
            count++;
        }
        if (q.contains("总结") || q.contains("汇总")) {
            count++;
        }
        if (q.contains("查") || q.contains("搜索")) {
            count++;
        }
        return count >= 2;
    }

    // ───────────────────── Stage 3：Tool Agent ─────────────────────

    private void runToolFromSet(ChatResponse resp, String query, Map<String, Tool> ts,
                                String memPrefix, List<Map<String, String>> histMsgs) {
        ToolCallResult tc = toolService.decide(query, ts);
        if (tc == null) { resp.setAnswer("我无法处理这个请求。"); return; }

        Tool tool = ts.get(tc.getToolName());
        if (tool == null) { resp.setAnswer("工具 " + tc.getToolName() + " 不存在"); resp.setToolCall(tc); return; }

        // 偏好感知参数自动填充
        fillParamsFromPreference(tc);

        try {
            String result = tool.getExecute().apply(tc.getParams());
            tc.setToolResult(result);
        } catch (Exception e) {
            resp.setAnswer("工具执行失败: " + e.getMessage());
            resp.setToolCall(tc);
            return;
        }

        // 用带记忆的 system prompt 生成自然语言回复
        String sp = buildSystemPrompt(memPrefix, "你是一个善于综合信息的AI助手。结合你掌握的用户信息，使回答更个性化。");
        String userMsg = String.format("用户问：%s\n工具 %s 返回结果：%s\n请根据结果自然地回答用户。",
                query, tc.getToolName(), tc.getToolResult());
        resp.setAnswer(llm.chat(sp, List.of(Map.of("role", "user", "content", userMsg))));
        resp.setToolCall(tc);
    }

    // ────────────────────────── Stage 4：ReAct ──────────────────────────

    private void runReActWithTools(ChatResponse resp, String query, Map<String, Tool> ts,
                                   String memPrefix, List<Map<String, String>> histMsgs) {
        List<ReActStep> reactSteps = new ArrayList<>();
        List<String> observations = new ArrayList<>();

        // Step 1: Planner LLM 决定调哪些工具及参数
        List<PlanItem> planItems = llmPlanSteps(query, ts, memPrefix);

        // 若 Planner 决定不需要任何工具，直接走 LLM 对话
        if (planItems.isEmpty()) {
            String sp = buildSystemPrompt(memPrefix, "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。");
            String answer = llm.chat(sp, histMsgs);
            reactSteps.add(new ReActStep(ReActStep.THOUGHT, "分析后无需调用工具，直接回答"));
            reactSteps.add(new ReActStep(ReActStep.FINAL_ANSWER, answer));
            resp.setAnswer(answer);
            resp.setSteps(reactSteps);
            return;
        }

        // 将 planItems 转换为 TaskStep 列表
        List<TaskStep> taskSteps = new ArrayList<>();
        for (int i = 0; i < planItems.size(); i++) {
            PlanItem pi = planItems.get(i);
            taskSteps.add(new TaskStep(i + 1, pi.reason, pi.tool, pi.params));
        }
        currentTask = new TaskState();
        currentTask.setTaskId("task-" + System.nanoTime());
        currentTask.setQuery(query);
        currentTask.setStatus("running");
        currentTask.setPhase("executing");
        currentTask.setSteps(taskSteps);
        snapshots.clear();
        saveSnapshot();

        // Step 2: 按 Planner 计划逐步执行工具
        for (int i = 0; i < currentTask.getSteps().size(); i++) {
            if (cancelled.get()) {
                currentTask.setPhase("interrupted");
                currentTask.setStatus("interrupted");
                currentTask.setInterruptedAt(i);
                // 将当前步骤标记为中断
                currentTask.getSteps().get(i).setStatus(TaskStep.INTERRUPTED);
                // 生成中断摘要
                String msg = buildInterruptMessage(currentTask);
                reactSteps.add(new ReActStep(ReActStep.OBSERVATION, "[已中断] " + msg));
                saveSnapshot();
                resp.setAnswer("[已中断] " + msg);
                resp.setSteps(reactSteps);
                resp.setTask(currentTask);
                resp.setInterrupted(true);
                return;
            }

            TaskStep step = currentTask.getSteps().get(i);
            currentTask.setCurrentStep(i);
            step.setStatus(TaskStep.RUNNING);

            // Thought：展示 Planner 给出的调用理由
            reactSteps.add(new ReActStep(ReActStep.THOUGHT, step.getName()));
            reactSteps.add(new ReActStep(ReActStep.ACTION, "调用 " + step.getToolName(), step.getToolName(), step.getParams()));

            Tool tool = ts.get(step.getToolName());
            if (tool == null) {
                step.setStatus(TaskStep.FAILED);
                step.setError("工具 " + step.getToolName() + " 不在允许列表中");
                reactSteps.add(new ReActStep(ReActStep.OBSERVATION, step.getError()));
                saveSnapshot();
                continue;
            }

            if (executeStepWithRetry(step, tool)) {
                step.setStatus(TaskStep.DONE);
                reactSteps.add(new ReActStep(ReActStep.OBSERVATION, step.getResult()));
                observations.add("[" + step.getToolName() + "] " + step.getResult());
            } else {
                if (cancelled.get()) {
                    step.setStatus(TaskStep.INTERRUPTED);
                    reactSteps.add(new ReActStep(ReActStep.OBSERVATION, "[已中断]"));
                } else {
                    step.setStatus(TaskStep.FAILED);
                    reactSteps.add(new ReActStep(ReActStep.OBSERVATION, "执行失败: " + step.getError()));
                }
            }
            saveSnapshot();
        }

        // Step 3: Generator LLM 综合所有观察结果生成最终答案
        if (cancelled.get()) {
            currentTask.setPhase("interrupted");
            currentTask.setStatus("interrupted");
            String msg = buildInterruptMessage(currentTask);
            resp.setAnswer("[已中断] " + msg);
            resp.setSteps(reactSteps);
            resp.setTask(currentTask);
            resp.setInterrupted(true);
            return;
        }

        currentTask.setPhase("generating");
        String answer = llmGenerate(query, observations, memPrefix, histMsgs);
        reactSteps.add(new ReActStep(ReActStep.FINAL_ANSWER, answer));
        currentTask.setResult(answer);
        currentTask.setStatus("completed");
        currentTask.setPhase("done");

        resp.setAnswer(answer);
        resp.setSteps(reactSteps);
        resp.setTask(currentTask);
    }

    /**
     * 调用 Planner LLM，从允许的工具集中智能选择需要调用的工具及参数
     * @param query
     * @param ts
     * @param memPrefix
     * @return
     */
    private List<PlanItem> llmPlanSteps(String query, Map<String, Tool> ts, String memPrefix) {
        if (!cfg.isRealLLM()) return rulePlanItems(query, ts);

        // 构造工具描述
        StringBuilder toolLines = new StringBuilder();
        for (Map.Entry<String, Tool> e : ts.entrySet()) {
            String name = e.getKey();
            Tool t = e.getValue();
            StringBuilder pDescs = new StringBuilder();
            if (t.getParameters() != null) {
                for (ToolParam p : t.getParameters()) {
                    if (pDescs.length() > 0) pDescs.append(", ");
                    pDescs.append(p.getName()).append("(").append(p.getType()).append(")");
                    if (p.isRequired()) pDescs.append("（必填）");
                }
            }
            toolLines.append("- ").append(name).append(": ").append(t.getDescription())
                    .append(" [参数: ").append(pDescs.length() > 0 ? pDescs : "无参数").append("]\n");
        }

        String planPrompt = String.format("""
                你是一个任务规划器。根据用户问题，从可用工具中选出真正需要调用的工具（不要为了用工具而用工具，按需选择）。

                用户问题：%s

                可用工具：
                %s
                请以 JSON 数组格式输出执行计划，格式如下：
                [{"tool":"工具名","params":{"参数名":"参数值"},"reason":"一句话说明为什么调用这个工具"}]

                如果无需工具直接回答，输出 []。只输出 JSON，不要其他内容。""", query, toolLines);

        String plannerBase = "你是一个精准的任务规划器，只在必要时才调用工具，不做无意义的调用。";
        if (!memPrefix.isEmpty()) {
            plannerBase = memPrefix + "\n\n" + plannerBase + "\n注意：用户偏好可能影响工具参数选择（如城市、时区等），请在参数中体现。";
        }

        String raw = llm.chat(plannerBase, List.of(Map.of("role", "user", "content", planPrompt)));
        // 清洗 LLM 输出（可能包含 markdown 代码块）
        raw = raw.trim()
                .replace("```json", "")
                .replace("```", "")
                .trim();

        try {
            List<Map<String, Object>> items = mapper.readValue(raw, mapper.getTypeFactory().constructCollectionType(List.class, Map.class));
            // 过滤：只保留工具集中实际存在的工具
            List<PlanItem> result = new ArrayList<>();
            for (Map<String, Object> item : items) {
                String tool = (String) item.get("tool");
                if (ts.containsKey(tool)) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> params = item.get("params") != null ?
                            (Map<String, String>) item.get("params") : new HashMap<>();
                    String reason = item.get("reason") != null ? item.get("reason").toString() : "调用" + tool;
                    result.add(new PlanItem(tool, params, reason));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Planner LLM 解析失败 ({}), 降级到规则规划", e.getMessage());
            return rulePlanItems(query, ts);
        }
    }

    /**
     * 关键词规则降级规划（无真实 LLM 时使用）
     * @param query
     * @param ts
     * @return
     */
    private List<PlanItem> rulePlanItems(String query, Map<String, Tool> ts) {
        String q = query.toLowerCase();
        List<PlanItem> items = new ArrayList<>();

        if (ts.containsKey("get_time") && (q.contains("时间") || q.contains("几点") || q.contains("现在"))) {
            Map<String, String> params = new HashMap<>();
            if (q.contains("北京")) params.put("timezone", "Asia/Shanghai");
            items.add(new PlanItem("get_time", params, "查询当前时间"));
        }
        if (ts.containsKey("get_weather") && q.contains("天气")) {
            String city = "北京";
            for (String c : List.of("东京", "北京", "上海", "广州", "深圳", "纽约", "伦敦")) {
                if (q.contains(c)) { city = c; break; }
            }
            items.add(new PlanItem("get_weather", Map.of("city", city), "查询" + city + "天气"));
        }
        if (ts.containsKey("search_web") && (q.contains("搜索") || q.contains("查询") || q.contains("介绍")
                || q.contains("是什么") || q.contains("怎么") || q.contains("如何"))) {
            items.add(new PlanItem("search_web", Map.of("query", query), "搜索相关信息"));
        }
        if (ts.containsKey("rag_search")) {
            items.add(new PlanItem("rag_search", Map.of("query", query), "检索个人知识库"));
        }
        // MCP拓展工具
        // 内置工具集合
        Set<String> builtins = new HashSet<>(Arrays.asList("get_time", "get_weather", "search_web", "rag_search"));
        for (Map.Entry<String, Tool> entry : ts.entrySet()) {
            String name = entry.getKey();
            Tool t = entry.getValue();
            if (builtins.contains(name)) {
                continue;
            }
            // 提取工具参数
            Map<String, String> params = extractParamsForTool(query, entry.getValue());
            items.add(new PlanItem(name, params, "调用工具 " + name));
        }
        return items;
    }

    /**
     * 用 LLM 从 query 中提取工具所需参数；无法调用时用 query 填充首个必填参数
     * @param query
     * @param t
     * @return
     */
    public Map<String, String> extractParamsForTool(String query, Tool t) {
        Map<String, String> result = new HashMap<>();

        // 如果工具没有参数，直接返回空map
        if (t.getParameters() == null || t.getParameters().isEmpty()) {
            return result;
        }

        // 如果不是真实的LLM环境，用query填充首个必填参数
        if (!cfg.isRealLLM()) {
            for (ToolParam p : t.getParameters()) {
                if (p.isRequired()) {
                    result.put(p.getName(), query);
                    break;
                }
            }
            return result;
        }

        // 构建参数说明
        List<String> lines = new ArrayList<>();
        for (ToolParam p : t.getParameters()) {
            String req = p.isRequired() ? "（必填）" : "";
            lines.add(String.format("- %s (%s)%s: %s", p.getName(), p.getType(), req, p.getDescription()));
        }

        // 构建提示词
        String prompt = String.format(
                "从下面的用户消息中提取工具「%s」所需的参数，以JSON对象格式输出，只输出JSON，不加任何说明。\n\n参数说明：\n%s\n\n用户消息：%s",
                t.getName(), String.join("\n", lines), query
        );

        // 调用LLM
        String raw = llm.chat("", List.of(Map.of("role", "user", "content", prompt)));
        raw = raw.replaceFirst("```json", "")
                        .replaceFirst("```", "").trim();

        try {
            // 解析JSON
            result = mapper.readValue(raw, Map.class);
        } catch (Exception e) {
            // LLM输出无法解析时兜底：用query填充首个必填参数
            result.clear();
            for (ToolParam p : t.getParameters()) {
                if (p.isRequired()) {
                    result.put(p.getName(), query);
                    break;
                }
            }
        }

        return result;
    }

    // ─────────────────────────────── Generator LLM ───────────────────────────────

    /**
     * 调用 Generator LLM，将多个工具观察结果合成为自然语言最终答案
     * @param query
     * @param observations
     * @param memPrefix
     * @param histMsgs
     * @return
     */
    private String llmGenerate(String query, List<String> observations, String memPrefix, List<Map<String, String>> histMsgs) {
        if (observations.isEmpty()) {
            String sp = buildSystemPrompt(memPrefix, "你是一个简洁的AI助手。结合你掌握的用户信息，使回答更个性化。");
            return llm.chat(sp, histMsgs);
        }
        if (!cfg.isRealLLM()) {
            return "综合查询结果：" + String.join("；", observations);
        }

        StringBuilder obs = new StringBuilder();
        for (int i = 0; i < observations.size(); i++) {
            obs.append(i + 1).append(". ").append(observations.get(i)).append("\n");
        }

        String genPrompt = String.format("""
                请根据以下工具执行结果，综合回答用户的问题。回答要自然流畅、重点突出，不要机械罗列原始数据，也不要重复问题本身。

                用户问题：%s

                工具执行结果：
                %s""", query, obs);

        String generatorBase = "你是一个善于综合信息的AI助手，能将多个工具的执行结果整合成清晰自然的回答。";
        if (!memPrefix.isEmpty()) {
            generatorBase = memPrefix + "\n\n" + generatorBase + "\n结合用户偏好，使回答更个性化。";
        }
        return llm.chat(generatorBase, List.of(Map.of("role", "user", "content", genPrompt)));
    }

    // ──────────────────────── Stage 6：Harness ────────────────────────

    /**
     * 带重试的步骤执行，使用传入的具体工具实例
     * @param step
     * @param tool
     * @return
     */
    private boolean executeStepWithRetry(TaskStep step, Tool tool) {
        Map<String, Object> params = new HashMap<>();
        if (step.getParams() != null) step.getParams().forEach(params::put);

        for (int attempt = 0; attempt < cfg.getHarness().getMaxRetries(); attempt++) {
            if (cancelled.get()) { step.setError("被用户中断"); return false; }
            try {
                String result = tool.getExecute().apply(params);
                step.setResult(result);
                return true;
            } catch (Exception e) {
                step.setRetryCount(attempt + 1);
                step.setError(e.getMessage());
                if (cancelled.get()) { step.setError("被用户中断"); return false; }
                try { Thread.sleep(cfg.getHarness().getRetryDelayMs()); } catch (InterruptedException ignored) {}
            }
        }
        return false;
    }

    // ──────────────────────── Stage 5：Memory（基础层，注入所有模式）────────────────────────

    /**
     * 构建包含偏好和长期记忆的 System Prompt 前缀
     * 仅使用当前已加载的偏好和长期记忆（无需实时 embedding）
     * @return
     */
    private String buildMemorySystemPrefix() {
        List<String> parts = new ArrayList<>();
        String prefCtx = pref.buildContext();
        if (!prefCtx.isEmpty()) parts.add(prefCtx);
        List<MemoryItem> ltmItems = ltm.getItems();
        if (!ltmItems.isEmpty()) {
            List<String> contents = ltmItems.stream().map(MemoryItem::getContent).toList();
            parts.add("【长期记忆】\n" + String.join("\n", contents));
        }
        return String.join("\n\n", parts);
    }

    /**
     * 构建带语义召回的记忆 System Prompt 前缀
     * 使用 embedding 做精准召回，只注入相关度超过阈值的长期记忆
     * @param query
     * @return
     */
    private String buildMemorySystemPrefixWithCtx(String query) {
        List<String> parts = new ArrayList<>();
        String prefCtx = pref.buildContext();
        if (!prefCtx.isEmpty()) parts.add(prefCtx);

        List<Double> queryEmb = llm.embed(query);
        List<MemoryItem> recalled = ltm.recall(query, cfg.getMemory().getLongTermTopK(), queryEmb);
        if (!recalled.isEmpty()) {
            List<String> contents = recalled.stream().map(MemoryItem::getContent).toList();
            parts.add("【相关记忆】\n" + String.join("\n", contents));
        }
        return String.join("\n\n", parts);
    }

    /**
     * 将 STM（短期记忆）历史消息转为 LLM 消息列表（末尾附上当前 user query）
     * @param query
     * @return
     */
    private List<Map<String, String>> buildHistoryMessages(String query) {
        List<Map<String, String>> msgs = new ArrayList<>();
        // STM 最后一条是刚加入的 user query，跳过重复
        for (ConversationMessage m : stm.getMessages()) {
            if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                msgs.add(Map.of("role", m.getRole(), "content", m.getContent()));
            }
        }
        // 如果最后一条不是当前 query（初次调用时 STM 已包含），则附上
        if (msgs.isEmpty() || !msgs.get(msgs.size() - 1).get("content").equals(query)) {
            msgs.add(Map.of("role", "user", "content", query));
        }
        return msgs;
    }

    /**
     * 构建带记忆前缀的 system prompt
     * @param memPrefix
     * @param basePrompt
     * @return
     */
    private String buildSystemPrompt(String memPrefix, String basePrompt) {
        if (memPrefix == null || memPrefix.isEmpty()) return basePrompt;
        return memPrefix + "\n\n" + basePrompt;
    }

    /**
     * 用户偏好自动补全工具调用参数中缺失的值
     * @param tc
     */
    private void fillParamsFromPreference(ToolCallResult tc) {
        if (tc == null || pref.getData().isEmpty()) return;
        Map<String, List<String>> prefToParam = Map.of(
                "城市", List.of("city", "location"),
                "时区", List.of("timezone", "tz"),
                "姓名", List.of("name", "username"),
                "语言", List.of("language", "lang"),
                "国家", List.of("country", "nation")
        );
        for (Map.Entry<String, List<String>> e : prefToParam.entrySet()) {
            String prefVal = pref.getData().get(e.getKey());
            if (prefVal == null || prefVal.isEmpty()) continue;
            for (String paramName : e.getValue()) {
                Object v = tc.getParams().get(paramName);
                if (v == null || v.toString().isEmpty()) {
                    tc.getParams().put(paramName, prefVal);
                }
            }
        }
    }

    /**
     * 从llm回复中提取值得记忆的信息并存入长期记忆
     * @param answer
     */
    private void extractMemoryFromReply(String answer) {
        if (answer == null || answer.isEmpty() || !cfg.isRealLLM()) return;
        try {
            String prompt = "从下面这段AI回复中，提取值得长期记住的客观事实或用户偏好信息。\n只提取明确的、非临时性的信息，忽略对话上下文和临时细节。\n输出 JSON 对象（key为中文名称，value为具体值），如果没有值得记忆的信息则输出 {}。\n只输出 JSON，不要有其他内容。\n\n回复：" + answer;
            String raw = llm.chat("", List.of(Map.of("role", "user", "content", prompt)));
            raw = raw.trim()
                    .replace("```json", "")
                    .replace("```", "").trim();
            @SuppressWarnings("unchecked")
            Map<String, String> kvs = mapper.readValue(raw, Map.class);
            for (Map.Entry<String, String> e : kvs.entrySet()) {
                if (e.getKey().isEmpty() || e.getValue().isEmpty()) continue;
                pref.save(e.getKey(), e.getValue());
                infra.savePreference("default", e.getKey(), e.getValue());
                String content = "用户" + e.getKey() + ": " + e.getValue();
                List<Double> emb = llm.embed(content);
                if (ltm.store(content, 0.7, emb)) {
                    String embJson = "null";
                    try { if (emb != null) embJson = mapper.writeValueAsString(emb); } catch (Exception ignored) {}
                    int pgId = infra.saveLongTermItem(content, 0.7, embJson);
                    ltm.syncLastItemPGID(pgId);
                }
                log.info("🧠 从回复中提取记忆：{} = {}", e.getKey(), e.getValue());
            }
        } catch (Exception ignored) {}
    }

    /**
     * 将记忆合并结果同步到 PostgreSQL
     * @param result
     */
    private void syncConsolidationToDB(LongTermMemory.ConsolidationResult result) {
        if (!result.deleteFromDB.isEmpty()) {
            infra.deleteLongTermItems(result.deleteFromDB);
            log.info("🧹 记忆合并：删除 {} 条（去重={}, 合并={}, 过期={}）",
                    result.deduped + result.merged + result.expired, result.deduped, result.merged, result.expired);
        }
        for (MemoryItem item : result.updateInDB) {
            String embJson = "null";
            try { if (item.getEmbedding() != null) embJson = mapper.writeValueAsString(item.getEmbedding()); } catch (Exception ignored) {}
            infra.updateLongTermItem(item.getId(), item.getContent(), item.getImportance(), embJson);
            log.info("🔗 记忆合并：更新 id={}", item.getId());
        }
    }

    // ────────────────────── Restore ──────────────────────

    /**
     * 启动时从 PostgreSQL 恢复跨会话偏好、长期记忆和聊天记录
     */
    private void restoreFromDB() {
        // 恢复偏好
        Map<String, String> prefs = infra.loadPreferences("default");
        pref.saveBatch(prefs);

        // 恢复长期记忆
        List<InfrastructureService.LongTermRow> rows = infra.loadLongTermItems();
        for (InfrastructureService.LongTermRow row : rows) {
            MemoryItem item = new MemoryItem();
            item.setId(row.id); item.setContent(row.content); item.setImportance(row.importance);
            item.setEmbedding(row.embedding);
            if (row.createdAt != null) item.setCreatedAt(row.createdAt.toLocalDateTime());
            if (row.lastAccessed != null) item.setLastAccessed(row.lastAccessed.toLocalDateTime());
            ltm.storeItem(item);
        }

        // 恢复聊天记录到短期记忆（最近 N 条）
        int chatLimit = cfg.getMemory().getShortTermMaxTurns() * 2;
        List<InfrastructureService.ChatHistoryRow> history = infra.loadChatHistory(chatLimit);
        for (InfrastructureService.ChatHistoryRow h : history) {
            stm.add(h.role, h.content);
        }

        if (!prefs.isEmpty() || !rows.isEmpty() || !history.isEmpty()) {
            log.info("✅ 记忆恢复：{} 条偏好，{} 条长期记忆，{} 条聊天记录", prefs.size(), rows.size(), history.size());
        }
    }

    /**
     * PostgreSQL 加载持久化的 RAG chunks 到 TF 兜底索引
     */
    private void restoreRAGFromDB() {
        List<InfrastructureService.ChunkRow> chunkRows = infra.loadAllRAGChunks();
        if (chunkRows.isEmpty()) return;
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < chunkRows.size(); i++) {
            chunks.add(new Chunk(i, chunkRows.get(i).content));
        }
        rag.restoreChunks(chunks);
        log.info("✅ RAG chunks 恢复：{} 条", chunks.size());
    }

    /**
     * 按名称列表过滤可用工具集
     * @param names
     * @return
     */
    private Map<String, Tool> filterTools(List<String> names) {
        Map<String, Tool> result = new HashMap<>();
        for (String name : names) {
            if (tools.containsKey(name)) result.put(name, tools.get(name));
        }
        return result;
    }

    /**
     * 对当前 TaskState 做深拷贝快照并持久化到 PG
     */
    private void saveSnapshot() {
        if (currentTask == null) return;
        try {
            String json = mapper.writeValueAsString(currentTask);
            TaskState copy = mapper.readValue(json, TaskState.class);
            snapshots.add(new Snapshot(copy, LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))));
            infra.saveSnapshot(currentTask.getTaskId(), json);
        } catch (Exception ignored) {}
    }

    /**
     * 根据已完成的步骤生成中断摘要
     * @param task
     * @return
     */
    private String buildInterruptMessage(TaskState task) {
        int done = 0;
        List<String> doneDesc = new ArrayList<>();
        List<String> pendingDesc = new ArrayList<>();
        for (TaskStep s : task.getSteps()) {
            if (TaskStep.DONE.equals(s.getStatus())) {
                done++;
                String r = s.getResult() != null && s.getResult().length() > 30 ? s.getResult().substring(0, 30) + "..." : s.getResult();
                doneDesc.add(s.getId() + "." + s.getToolName() + "→" + r);
            } else {
                pendingDesc.add(s.getId() + "." + s.getToolName());
            }
        }
        StringBuilder msg = new StringBuilder("已完成 " + done + "/" + task.getSteps().size() + " 步");
        if (!doneDesc.isEmpty()) msg.append("：").append(String.join("；", doneDesc));
        if (!pendingDesc.isEmpty()) msg.append("；未执行：").append(String.join("、", pendingDesc));
        return msg.toString();
    }

    private List<ChatResponse.SearchResultDto> toSearchResults(List<RagService.ScoredChunk> results) {
        if (results == null) return null;
        return results.stream()
                .map(r -> new ChatResponse.SearchResultDto(r.chunk, r.score))
                .toList();
    }

    /**
     * 调用 Tavily Search API，返回格式化的搜索结果摘要
     * @param query
     * @param apiKey
     * @param apiUrl
     * @return
     * @throws Exception
     */
    static String tavilySearch(String query, String apiKey, String apiUrl) throws Exception {
        // 基本参数
        Map<String, Object> body = Map.of("api_key", apiKey, "query", query, "search_depth", "basic", "max_results", 5);
        String json = mapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();

        // 搜索请求
        log.info("MCP搜索工具入参：{}", json);
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Tavily 返回错误状态: " + response.code());
            }
            String respBody = Optional.ofNullable(response.body().string()).orElse("");
            Map<String, Object> result = mapper.readValue(respBody, Map.class);
            log.info("MCP搜索工具返回：{}", JSON.toJSONString(result));
            // 优先返回 Tavily 合成的 answer
            String answer = (String) result.get("answer");
            if (answer != null && !answer.isEmpty()) {
                return answer;
            }
            // 无 answer 时拼接 top3 结果摘要
            @SuppressWarnings("unchecked")
            List<Map<String, String>> results = (List<Map<String, String>>) result.get("results");
            if (results != null && !results.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(3, results.size()); i++) {
                    sb.append("**").append(results.get(i).get("title")).append("**\n");
                    sb.append(results.get(i).get("content")).append("\n\n");
                }
                return sb.toString().trim();
            }
            throw new RuntimeException("Tavily 返回空结果");
        }
    }

    /**
     * Planner LLM 输出的单个工具调用计划
     */
    private static class PlanItem {
        String tool;
        Map<String, String> params;
        String reason;
        PlanItem(String tool, Map<String, String> params, String reason) {
            this.tool = tool; this.params = params; this.reason = reason;
        }
    }
}
