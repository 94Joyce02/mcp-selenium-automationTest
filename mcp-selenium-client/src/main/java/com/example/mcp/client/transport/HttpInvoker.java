
package com.example.mcp.client.transport;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class HttpInvoker implements TransportInvoker {
    private final String baseUrl;
    private final String clientId;
    private final RestTemplate rest = new RestTemplate();

    public HttpInvoker(@Value("${mcp.server.httpBaseUrl:http://localhost:18081}") String baseUrl,
                       @Value("${mcp.clientId:demo-client}") String clientId) {
        this.baseUrl = baseUrl;
        this.clientId = clientId;
    }

    @Override
    public Map<String, Object> execute(List<Map<String, Object>> actions) throws IOException {
        Map<String, Object> req = new HashMap<>();
        req.put("clientId", clientId);
        req.put("method", "execute");
        req.put("actions", actions);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = rest.exchange(
                baseUrl + "/api/execute",
                HttpMethod.POST,
                new HttpEntity<>(req, headers),
                Map.class
        );
        if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
            return resp.getBody();
        }
        throw new IOException("HTTP server error: " + resp.getStatusCode());
    }
}
