package com.example.mcp.client.web;

import com.example.mcp.client.assist.SelectorSensingService;
import com.example.mcp.client.llm.ActionPlan;
import com.example.mcp.client.llm.LlmActionPlanner;
import com.example.mcp.client.llm.NaturalInstructionParser;
import com.example.mcp.client.transport.InvokerFactory;
import com.example.mcp.client.transport.StdioInvoker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class CommandController {

    private final InvokerFactory invokerFactory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final NaturalInstructionParser instructionParser;
    private final LlmActionPlanner llmPlanner;
    private final SelectorSensingService sensingService;

    public CommandController(
            InvokerFactory invokerFactory,
            Optional<NaturalInstructionParser> instructionParser,
            Optional<LlmActionPlanner> llmPlanner,
            Optional<SelectorSensingService> sensingService) {
        this.invokerFactory = invokerFactory;
        this.instructionParser = instructionParser.orElse(null);
        this.llmPlanner = llmPlanner.orElse(null);
        this.sensingService = sensingService.orElse(null);
    }

    @PostMapping("/execute")
    public Map<String, Object> execute(
            @RequestBody Map<String, Object> body,
            @RequestParam(name = "fresh", defaultValue = "false") boolean fresh,
            @RequestParam(name = "stopOnError", defaultValue = "true") boolean stopOnError,
            @RequestParam(name = "sessionId", required = false) String sessionId) throws Exception {

        // 1) fresh 会话：显式重启（仅 stdio 有意义）
        var invoker = invokerFactory.get();
        if (fresh && invoker instanceof StdioInvoker si) {
            si.restart();
        }

        // 2) 解析自然语言 -> actions（省略：你现有 NaturalInstructionParser / LLM 输出解析）
        // 这里假设 body 里已是 actions（前端也可直接传）
        List<Map<String, Object>> selectorHints = extractSelectorHints(body.get("selectorHints"));
        Object raw = body.getOrDefault("actions", body.get("plan"));
        if (raw == null && body.get("prompt") instanceof String prompt && !prompt.isBlank()) {
            if ((selectorHints == null || selectorHints.isEmpty()) && sensingService != null) {
                List<Map<String, Object>> autoHints = sensingService.senseFromPrompt(prompt);
                if (!autoHints.isEmpty()) {
                    selectorHints = autoHints;
                }
            }
            List<Map<String, Object>> llmActions = llmPlanner == null
                    ? null
                    : llmPlanner.plan(prompt, selectorHints).orElse(null);
            if (llmActions != null && !llmActions.isEmpty()) {
                raw = llmActions;
            } else {
                if (instructionParser == null) {
                    throw new IllegalArgumentException("Missing 'actions' and no parser available");
                }
                ActionPlan plan = instructionParser.parse(prompt);
                raw = plan;
            }
        }
        List<Map<String, Object>> actions = coerceToActions(raw);
        if (actions.isEmpty()) {
            throw new IllegalArgumentException("No actions available to execute");
        }

        // 3) 分步执行（推荐）
        if (invoker instanceof StdioInvoker si) {
            try {
                List<Map<String, Object>> steps = si.executeStepwise(actions, stopOnError, sessionId);
                boolean allOk = steps.stream().allMatch(this::isOk);
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("ok", allOk);
                if (sessionId != null && !sessionId.isBlank()) {
                    resp.put("sessionId", sessionId);
                }
                resp.put("steps", steps);
                return resp;
            } catch (Exception e) {
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("ok", false);
                if (sessionId != null && !sessionId.isBlank()) {
                    resp.put("sessionId", sessionId);
                }
                resp.put("error", "STDIO transport failed: " + e.getMessage());
                return resp;
            }
        }

        // 4) HTTP 场景：仍可一次性提交（或你也能实现 HttpInvoker.executeOne + 步进）
        Map<String, Object> oneShot = invoker.execute(actions);
        return Map.of("ok", isOk(oneShot), "result", oneShot);
    }

    private boolean isOk(Map<String, Object> resp) {
        if (resp == null)
            return false;
        if (Boolean.TRUE.equals(resp.get("ok")))
            return true;
        Object st = resp.get("status");
        return st != null && "ok".equalsIgnoreCase(String.valueOf(st));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> coerceToActions(Object raw) {
        if (raw == null)
            throw new IllegalArgumentException("Missing 'actions'");

        if (raw instanceof List<?> l) {
            // list of maps or strings -> 统一成 map
            List<Map<String, Object>> out = new ArrayList<>();
            int i = 0;
            for (Object o : l) {
                if (o instanceof Map)
                    out.add((Map<String, Object>) o);
                else if (o instanceof ActionPlan.Action action)
                    out.add(actionToMap(action, i++));
                else if (o instanceof String s)
                    out.add(Map.of("type", "cmd", "value", s, "_source", "string", "_idx", i++));
                else
                    out.add(Map.of("type", "unknown", "value", String.valueOf(o), "_idx", i++));
            }
            return out;
        }

        if (raw instanceof ActionPlan plan) {
            List<Map<String, Object>> out = new ArrayList<>();
            int idx = 0;
            for (ActionPlan.Action action : plan.getActions()) {
                out.add(actionToMap(action, idx++));
            }
            return out;
        }

        if (raw instanceof Map) {
            // 服务端/LLM 可能给的是 {actions:[...]}
            Object inner = ((Map<?, ?>) raw).get("actions");
            if (inner instanceof List)
                return (List<Map<String, Object>>) inner;
        }

        if (raw instanceof String s) {
            // 兼容代码块/字符串：尽力解析成 JSON 数组
            String t = s.trim();
            try {
                if (t.startsWith("```"))
                    t = t.substring(t.indexOf('\n') + 1, t.lastIndexOf("```")).trim();
                Object any = mapper.readValue(t, Object.class);
                return coerceToActions(any);
            } catch (Exception ignored) {
                // 兜底：单行动作
                return List.of(Map.of("type", "cmd", "value", t, "_source", "raw-string"));
            }
        }

        throw new IllegalArgumentException("Cannot coerce actions from: " + raw.getClass());
    }

    private Map<String, Object> actionToMap(ActionPlan.Action action, int idx) {
        if (action == null) {
            return Map.of("type", "unknown", "_source", "natural-parser", "_idx", idx);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", action.type != null ? action.type : "unknown");
        if (action.url != null)
            map.put("url", action.url);
        if (action.selector != null)
            map.put("selector", action.selector);
        if (action.text != null)
            map.put("text", action.text);
        if (action.by != null)
            map.put("by", action.by);
        if (action.browser != null)
            map.put("browser", action.browser);
        if (action.headless != null)
            map.put("headless", action.headless);
        if (action.filename != null)
            map.put("filename", action.filename);
        map.put("_source", "natural-parser");
        map.put("_idx", idx);
        return map;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractSelectorHints(Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                    out.add(copy);
                }
            }
            return out;
        }
        if (raw instanceof Map<?, ?> single) {
            Map<String, Object> copy = new LinkedHashMap<>();
            single.forEach((k, v) -> copy.put(String.valueOf(k), v));
            return List.of(copy);
        }
        return List.of();
    }
}
