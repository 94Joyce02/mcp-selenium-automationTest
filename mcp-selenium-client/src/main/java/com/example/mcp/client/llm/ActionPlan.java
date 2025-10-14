package com.example.mcp.client.llm;

import java.util.ArrayList;
import java.util.List;

public class ActionPlan {

    public static class Action {
        // 基础字段
        public String type;
        public String url;
        public String selector;
        public String text;
        public String by;

        // 扩展字段（用于更丰富的动作参数）
        public String browser; // "chrome" | "edge" | "firefox"
        public Boolean headless; // true | false
        public String filename; // for screenshot
    }

    private final List<Action> actions = new ArrayList<>();

    public List<Action> getActions() {
        return actions;
    }
}
