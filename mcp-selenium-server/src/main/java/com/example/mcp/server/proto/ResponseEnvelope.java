
package com.example.mcp.server.proto;

import java.util.HashMap;
import java.util.Map;

public class ResponseEnvelope {
    private String status; // ok | error
    private String message;
    private Map<String, Object> data = new HashMap<>();

    public static ResponseEnvelope ok(String message) {
        ResponseEnvelope r = new ResponseEnvelope();
        r.status = "ok";
        r.message = message;
        return r;
    }
    public static ResponseEnvelope error(String message) {
        ResponseEnvelope r = new ResponseEnvelope();
        r.status = "error";
        r.message = message;
        return r;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
}
