package com.example.mcp.client.web;
import com.example.mcp.client.transport.InvokerFactory;
import com.example.mcp.client.transport.StdioInvoker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class CommandController {

    private final InvokerFactory invokerFactory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatClient.Builder chatBuilder; // 可空

    public CommandController(InvokerFactory invokerFactory, Optional<ChatClient.Builder> chatBuilder) {
        this.invokerFactory = invokerFactory;
        this.chatBuilder = chatBuilder.orElse(null);
    }

    @PostMapping("/execute")
    public Map<String, Object> execute(
            @RequestBody Map<String, Object> body,
            @RequestParam(name = "fresh", defaultValue = "false") boolean fresh,
            @RequestParam(name = "stopOnError", defaultValue = "true") boolean stopOnError,
            @RequestParam(name = "sessionId", required = false) String sessionId
    ) throws Exception {

        // 1) fresh 会话：显式重启（仅 stdio 有意义）
        var invoker = invokerFactory.get();
        if (fresh && invoker instanceof StdioInvoker si) {
            si.restart();
        }

        // 2) 解析自然语言 -> actions（省略：你现有 NaturalInstructionParser / LLM 输出解析）
        // 这里假设 body 里已是 actions（前端也可直接传）
        Object raw = body.getOrDefault("actions", body.get("plan"));
        List<Map<String, Object>> actions = coerceToActions(raw);

        // 3) 分步执行（推荐）
        if (invoker instanceof StdioInvoker si) {
            List<Map<String, Object>> steps = si.executeStepwise(actions, stopOnError, sessionId);
            boolean allOk = steps.stream().allMatch(this::isOk);
            return Map.of(
                    "ok", allOk,
                    "sessionId", (sessionId == null || sessionId.isBlank()) ? null : sessionId,
                    "steps", steps
            );
        }

        // 4) HTTP 场景：仍可一次性提交（或你也能实现 HttpInvoker.executeOne + 步进）
        Map<String, Object> oneShot = invoker.execute(actions);
        return Map.of("ok", isOk(oneShot), "result", oneShot);
    }

    private boolean isOk(Map<String, Object> resp) {
        if (resp == null) return false;
        if (Boolean.TRUE.equals(resp.get("ok"))) return true;
        Object st = resp.get("status");
        return st != null && "ok".equalsIgnoreCase(String.valueOf(st));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> coerceToActions(Object raw) {
        if (raw == null) throw new IllegalArgumentException("Missing 'actions'");

        if (raw instanceof List<?> l) {
            // list of maps or strings -> 统一成 map
            List<Map<String,Object>> out = new ArrayList<>();
            int i=0;
            for (Object o : l) {
                if (o instanceof Map) out.add((Map<String,Object>)o);
                else if (o instanceof String s) out.add(Map.of("type","cmd","value",s, "_source","string", "_idx", i++));
                else out.add(Map.of("type","unknown","value", String.valueOf(o), "_idx", i++));
            }
            return out;
        }

        if (raw instanceof Map) {
            // 服务端/LLM 可能给的是 {actions:[...]}
            Object inner = ((Map<?,?>) raw).get("actions");
            if (inner instanceof List) return (List<Map<String,Object>>) inner;
        }

        if (raw instanceof String s) {
            // 兼容代码块/字符串：尽力解析成 JSON 数组
            String t = s.trim();
            try {
                if (t.startsWith("```")) t = t.substring(t.indexOf('\n')+1, t.lastIndexOf("```")).trim();
                Object any = mapper.readValue(t, Object.class);
                return coerceToActions(any);
            } catch (Exception ignored) {
                // 兜底：单行动作
                return List.of(Map.of("type","cmd","value", t, "_source","raw-string"));
            }
        }

        throw new IllegalArgumentException("Cannot coerce actions from: " + raw.getClass());
    }
}
