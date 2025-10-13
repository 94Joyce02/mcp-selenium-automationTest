
package com.example.mcp.client.llm;

import org.springframework.stereotype.Component;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class NaturalInstructionParser {
    private static final Pattern URL = Pattern.compile("(https?://\\S+)");

    public ActionPlan parse(String input) {
        String s = input == null ? "" : input.toLowerCase();
        ActionPlan plan = new ActionPlan();

        if (s.contains("open") || s.contains("launch")) {
            ActionPlan.Action a = new ActionPlan.Action();
            a.type = "open_browser";
            plan.getActions().add(a);
        }
        Matcher m = URL.matcher(input == null ? "" : input);
        if (m.find()) {
            ActionPlan.Action a = new ActionPlan.Action();
            a.type = "goto";
            a.url = m.group(1);
            plan.getActions().add(a);
        }
        if (s.contains("screenshot") || s.contains("capture")) {
            ActionPlan.Action a = new ActionPlan.Action();
            a.type = "screenshot";
            plan.getActions().add(a);
        }
        return plan;
    }
}
