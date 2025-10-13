
package com.example.mcp.client.transport;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface TransportInvoker {
    Map<String, Object> execute(List<Map<String, Object>> actions) throws IOException;
}
