package com.example.mcp.client.stdio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 管理底层 stdio 进程；支持惰性启动/重启/单步执行 */
@Component
public class StdioServerProcess implements Closeable {

    private final List<String> command;
    private final String clientId;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile Process proc;
    private volatile BufferedWriter toServer;
    private volatile BufferedReader fromServer;

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
        String[] list = env.getProperty("mcp.server.command.list", String[].class);

        if (list != null && list.length > 0) {
            this.command = Arrays.asList(list);
        } else if (cmdCsv != null && !cmdCsv.isBlank()) {
            // 其次：逗号分隔（你 yml 里就是这种）
            // 注意逗号后常有空格，记得 trim
            this.command = Arrays.stream(cmdCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } else if (legacyCmdLine != null && !legacyCmdLine.isBlank()) {
            // 兜底：旧字段空格分隔
            this.command = Arrays.asList(legacyCmdLine.trim().split("\\s+"));
        } else {
            // 最终兜底：直接找 mcp-selenium 全局命令
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
        ProcessBuilder pb = new ProcessBuilder(this.command);
        pb.redirectErrorStream(false);
        proc = pb.start();
        toServer   = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
        fromServer = new BufferedReader(new InputStreamReader(proc.getInputStream(),  StandardCharsets.UTF_8));
    }

    public synchronized void restart() throws IOException {
        close();
        ensureStarted();
    }

    public Map<String, Object> rpcExecuteOne(Map<String, Object> action, String sessionId, int stepIndex, long timeoutMs) throws IOException {
        ensureStarted();
        Map<String, Object> req = new HashMap<>();
        req.put("method", "execute");
        req.put("sessionId", sessionId);
        req.put("stepIndex", stepIndex);
        req.put("clientId", clientId);
        req.put("action", action);

        String line = sendAndReadLine(req, timeoutMs);
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
        Map<String, Object> req = Map.of("method", "execute", "actions", actions, "clientId", clientId);
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
        toServer.write(json);
        toServer.newLine();
        toServer.flush();

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("Server timed out while waiting response");
            }
            if (!fromServer.ready()) {
                if (proc != null && !proc.isAlive()) {
                    String err = readAll(proc.getErrorStream());
                    throw new IOException("Server process exited. STDERR:\n" + err);
                }
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
                continue;
            }
            String line = fromServer.readLine();
            if (line == null) {
                String err = readAll(proc.getErrorStream());
                throw new IOException("Server closed output (EOF). STDERR:\n" + err);
            }
            line = line.trim();
            if (line.isEmpty()) continue;
            return line;
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void close() throws IOException {
        if (toServer != null)   try { toServer.close(); }   catch (Exception ignored) {}
        if (fromServer != null) try { fromServer.close(); } catch (Exception ignored) {}
        if (proc != null) {
            try { proc.getErrorStream().close(); } catch (Exception ignored) {}
            proc.destroy();
        }
        proc = null;
        toServer = null;
        fromServer = null;
    }
}
