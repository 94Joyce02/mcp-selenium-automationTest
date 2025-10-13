
package com.example.mcp.server.proto;

import java.util.List;

public class RequestEnvelope {
    private String clientId;
    private String method; // "execute"
    private List<Action> actions;

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public List<Action> getActions() { return actions; }
    public void setActions(List<Action> actions) { this.actions = actions; }
}
