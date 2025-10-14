
package com.example.mcp.server.proto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RequestEnvelope {
    private String clientId;
    private String method; // "execute"
    private List<Action> actions;
    private String sessionId;
    private Integer stepIndex;
    private Boolean sessionDone;

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public List<Action> getActions() { return actions; }
    public void setActions(List<Action> actions) { this.actions = actions; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Integer getStepIndex() { return stepIndex; }
    public void setStepIndex(Integer stepIndex) { this.stepIndex = stepIndex; }
    public Boolean getSessionDone() { return sessionDone; }
    public void setSessionDone(Boolean sessionDone) { this.sessionDone = sessionDone; }
}
