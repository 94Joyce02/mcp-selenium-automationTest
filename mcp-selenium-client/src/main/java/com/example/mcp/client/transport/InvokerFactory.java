
package com.example.mcp.client.transport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InvokerFactory {
    private final String transport; // stdio | http
    private final StdioInvoker stdio;
    private final HttpInvoker http;

    public InvokerFactory(@Value("${mcp.transport:stdio}") String transport,
                          StdioInvoker stdio,
                          HttpInvoker http) {
        this.transport = transport;
        this.stdio = stdio;
        this.http = http;
    }

    public TransportInvoker get() {
        if ("http".equalsIgnoreCase(transport)) return http;
        return stdio;
    }
}
