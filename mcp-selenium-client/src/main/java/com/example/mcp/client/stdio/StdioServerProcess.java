package com.example.mcp.client.stdio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 管理底层 stdio 进程；支持惰性启动/重启/单步执行 */
@Component
public class StdioServerProcess implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(StdioServerProcess.class);

    private final List<String> command;
    private final String clientId;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile Process proc;
    private volatile BufferedWriter toServer;
    private volatile BufferedReader fromServer;
    private volatile Thread stderrDrainer;

    // ✅ 用最简单、安全的方式注入；其它组合逻辑放到构造方法里处理
    public StdioServerProcess(
            Environment env,
            // 兼容：你现在用的逗号分隔写法
            @Value("${mcp.server.command:}") String cmdCsv,
            // 兼容旧字段（如果有人还用 mcp.stdio.command）
            @Value("${mcp.stdio.command:}") String legacyCmdLine,
            @Value("${mcp.clientId:}") String clientIdFromCfg
    ) {
        // 优先：数组方式 mcp.server.command.list: [ "java", "-jar", "...", "--opt=..." ]
        List<String> list = Binder.get(env)
                .bind("mcp.server.command.list", Bindable.listOf(String.class))
                .orElseGet(Collections::emptyList);

        if (!list.isEmpty()) {
            log.info("Using mcp.server.command.list from configuration ({} entries)", list.size());
            this.command = List.copyOf(list);
        } else if (cmdCsv != null && !cmdCsv.isBlank()) {
            // 其次：逗号分隔（你 yml 里就是这种）
            // 注意逗号后常有空格，记得 trim
            log.info("Using mcp.server.command (CSV) from configuration");
            this.command = Arrays.stream(cmdCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } else if (legacyCmdLine != null && !legacyCmdLine.isBlank()) {
            // 兜底：旧字段空格分隔
            log.info("Using mcp.stdio.command (legacy) from configuration");
            this.command = Arrays.asList(legacyCmdLine.trim().split("\\s+"));
        } else {
            // 最终兜底：直接找 mcp-selenium 全局命令
            log.warn("No server command configured; defaulting to ~/.npm-global/bin/mcp-selenium --stdio");
            this.command = List.of(System.getProperty("user.home") + "/.npm-global/bin/mcp-selenium", "--stdio");
        }

        String cid = (clientIdFromCfg == null || clientIdFromCfg.isBlank())
                ? env.getProperty("mcp.clientId")
                : clientIdFromCfg;
        this.clientId = (cid == null || cid.isBlank())
                ? "client-" + java.time.Instant.now().toEpochMilli()
                : cid;
    }

    private synchronized void ensureStarted() throws IOException {
        if (proc != null && proc.isAlive()) return;
        log.info("Starting MCP STDIO server: {}", String.join(" ", this.command));
        ProcessBuilder pb = new ProcessBuilder(this.command);
        pb.redirectErrorStream(false);
        proc = pb.start();
        Process current = proc;
        toServer   = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        fromServer = new BufferedReader(new InputStreamReader(proc.getInputStream(),  StandardCharsets.UTF_8));
        drainStderr(proc.getErrorStream());
        current.onExit().thenRun(() -> {
            int exit = current.exitValue();
            log.warn("MCP STDIO server exited with code {}", exit);
        });
    }

    public synchronized void restart() throws IOException {
        close();
        ensureStarted();
    }

    public Map<String, Object> rpcExecuteOne(Map<String, Object> action, String sessionId, int stepIndex, boolean sessionDone, long timeoutMs) throws IOException {
        ensureStarted();
        Map<String, Object> req = new HashMap<>();
        req.put("method", "execute");
        req.put("clientId", clientId);
        req.put("sessionId", sessionId);
        req.put("stepIndex", stepIndex);
        req.put("sessionDone", sessionDone);
        req.put("actions", List.of(action));

        long effectiveTimeout = timeoutMs;
        Object timeoutHint = action == null ? null : action.get("timeoutMs");
        if (timeoutHint instanceof Number n) {
            effectiveTimeout = Math.max(timeoutMs, n.longValue());
        } else if (timeoutHint instanceof String s) {
            try {
                effectiveTimeout = Math.max(timeoutMs, Long.parseLong(s));
            } catch (NumberFormatException ignored) {
            }
        }

        String line = sendAndReadLine(req, effectiveTimeout);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = mapper.readValue(line, Map.class);
            return resp;
        } catch (Exception e) {
            throw new IOException("Invalid server response: " + line, e);
        }
    }

    public Map<String, Object> rpcExecute(List<Map<String, Object>> actions) throws IOException {
        ensureStarted();
        Map<String, Object> req = new HashMap<>();
        req.put("method", "execute");
        req.put("clientId", clientId);
        req.put("actions", actions);
        req.put("sessionDone", true);
        String line = sendAndReadLine(req, 60_000);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = mapper.readValue(line, Map.class);
            return resp;
        } catch (Exception e) {
            throw new IOException("Invalid server response: " + line, e);
        }
    }

    private String sendAndReadLine(Map<String, Object> req, long timeoutMs) throws IOException {
        String json = mapper.writeValueAsString(req);
        if (log.isDebugEnabled()) {
            log.debug("→ STDIO {}", json);
        }
        toServer.write(json);
        toServer.newLine();
        toServer.flush();

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                log.error("STDIO server timed out ({}ms) waiting for response", timeoutMs);
                throw new IOException("Server timed out while waiting response");
            }
            if (!fromServer.ready()) {
                if (proc != null && !proc.isAlive()) {
                    int exitValue = proc.exitValue();
                    throw new IOException("Server process exited unexpectedly (exitCode=" + exitValue + "). Check server logs.");
                }
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                continue;
            }
            String line = fromServer.readLine();
            if (line == null) {
                throw new IOException("Server closed output (EOF). Check server logs.");
            }
            line = line.trim();
            if (line.isEmpty()) continue;
            if (log.isDebugEnabled()) {
                log.debug("← STDIO {}", line);
            }
            return line;
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (toServer != null)   try { toServer.close(); }   catch (Exception ignored) {}
        if (fromServer != null) try { fromServer.close(); } catch (Exception ignored) {}
        if (proc != null) {
            try { proc.getErrorStream().close(); } catch (Exception ignored) {}
            proc.destroy();
        }
        if (stderrDrainer != null) {
            stderrDrainer.interrupt();
        }
        proc = null;
        toServer = null;
        fromServer = null;
        stderrDrainer = null;
    }

    private synchronized void drainStderr(InputStream errStream) {
        if (stderrDrainer != null && stderrDrainer.isAlive()) {
            return;
        }
        stderrDrainer = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(errStream, StandardCharsets.UTF_8))) {
                String line;
                while (!Thread.currentThread().isInterrupted() && (line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        log.warn("[STDIO server] {}", trimmed);
                    }
                }
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    log.debug("STDIO server stderr reader stopped", e);
                }
            }
        }, "mcp-stdio-server-stderr");
        stderrDrainer.setDaemon(true);
        stderrDrainer.start();
    }
}
