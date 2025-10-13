package com.example.mcp.client.transport;
import com.example.mcp.client.stdio.StdioServerProcess;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class StdioInvoker implements TransportInvoker {
    private final StdioServerProcess stdio;
    public StdioInvoker(StdioServerProcess stdio) { this.stdio = stdio; }

    // 新增：分步执行，带 stopOnError / sessionId
    public List<Map<String, Object>> executeStepwise(
            List<Map<String, Object>> actions,
            boolean stopOnError,
            String sessionId
    ) throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();
        String sid = (sessionId != null && !sessionId.isBlank()) ? sessionId : UUID.randomUUID().toString();

        for (int i = 0; i < actions.size(); i++) {
            Map<String, Object> action = actions.get(i);
            Map<String, Object> resp = stdio.rpcExecuteOne(action, sid, i, 60_000);
            results.add(resp);

            boolean ok = Boolean.TRUE.equals(resp.get("ok")) // 建议服务端严格返回 ok=true/false
                    || "ok".equalsIgnoreCase(String.valueOf(resp.get("status")));
            if (!ok && stopOnError) break;
        }
        return results;
    }

    @Override
    public Map<String, Object> execute(List<Map<String, Object>> actions) throws IOException {
        // 兼容旧接口：仍然允许一次性提交（不建议）
        return stdio.rpcExecute(actions);
    }

    public void restart() throws IOException {
        stdio.restart();
    }
}
