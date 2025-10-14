package com.example.mcp.client.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class LlmActionPlanner {
    private static final Logger log = LoggerFactory.getLogger(LlmActionPlanner.class);
    private static final String SYSTEM_PROMPT = """
            You are an expert Selenium/MCP planner. Convert multi-step natural language test cases into a JSON plan the automation runtime can execute.
            Rules you MUST follow:
            1. Preserve the original step order; emit actions exactly in the sequence required to satisfy each step. Treat a single user-provided test case as one browser session.
            2. Start the session with a single `open_browser` action (honour hints like "headless equals false" by setting the `headless` field). Only include download directory when explicitly requested.
            3. Supported action types: open_browser, goto, wait, wait_for_selector, type, key_press, click, screenshot, find_text, scroll_by, scroll_to, set_download_dir, get_title, get_current_url.
               - Use `wait_for_selector` with a realistic CSS selector whenever the user says things like "after the page loads" or "wait for results".
               - Use `type` together with a `key_press` (for ENTER) when asked to perform a search.
               - Use `screenshot` for capture instructions and include a descriptive `note`.
            4. Provide useful `selector` values (CSS preferred, XPath only if necessary). Include the `by` field when you use XPath.
            5. Add optional `note` strings to clarify intent or mapping from the natural language instruction.
            6. Response MUST be valid JSON only, no prose, no code fences. Schema:
               {
                 "actions": [
                   {
                     "type": "...",
                     "url": "...",
                     "selector": "...",
                     "by": "...",
                     "text": "...",
                     "timeoutMs": 5000,
                     "headless": false,
                     "note": "..."
                   }
                 ]
               }
            Ignore any unknown fields in the schema. Only include keys that are relevant for the given action.
            7. When selector hints are provided, treat them as authoritative descriptions of elements captured from the live page. Prefer them over synthesising new selectors.
            """;
    private final ChatClient.Builder builder;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmActionPlanner(Optional<ChatClient.Builder> builder) {
        this.builder = builder.orElse(null);
    }

    public Optional<List<Map<String, Object>>> plan(String instructions) {
        return plan(instructions, null);
    }

    public Optional<List<Map<String, Object>>> plan(String instructions,
                                                    List<Map<String, Object>> selectorHints) {
        if (builder == null || instructions == null || instructions.isBlank()) {
            return Optional.empty();
        }
        try {
            String userPrompt = buildUserPrompt(instructions, selectorHints);
            String raw = builder.build()
                    .prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt)
                    .call()
                    .content();
            if (raw == null) {
                return Optional.empty();
            }
            String json = stripCodeFence(raw.trim());
            Map<String, Object> root = mapper.readValue(json, new TypeReference<>() {
            });
            Object actions = root.get("actions");
            if (actions instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> maps = mapper.convertValue(list,
                        new TypeReference<List<Map<String, Object>>>() {
                        });
                return Optional.of(maps);
            }
        } catch (Exception e) {
            log.warn("LLM action plan generation failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private String buildUserPrompt(String instructions,
                                   List<Map<String, Object>> selectorHints) {
        StringBuilder sb = new StringBuilder();
        sb.append("Create a Selenium action plan for the following steps:\n")
                .append(instructions.trim());
        if (selectorHints != null && !selectorHints.isEmpty()) {
            sb.append("\n\nYou must prefer the following selector hints when they match the intent. ")
                    .append("Each entry has selector, tag, score, and key attributes to help you choose the right element:\n");
            int count = 0;
            for (Map<String, Object> hint : selectorHints) {
                if (count++ >= 10) {
                    break;
                }
                sb.append("- selector: ").append(safeString(hint.get("selector")))
                        .append(", tag: ").append(safeString(hint.get("tag")))
                        .append(", score: ").append(safeString(hint.get("score")));
                Object attrs = hint.get("attributes");
                if (attrs instanceof Map<?, ?> attrMap && !attrMap.isEmpty()) {
                    sb.append(", attributes: ");
                    attrMap.forEach((k, v) -> sb.append(k).append('=').append(safeString(v)).append(';'));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private String safeString(Object value) {
        if (value == null) {
            return "";
        }
        String str = String.valueOf(value);
        return str.replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String stripCodeFence(String content) {
        if (content.startsWith("```")) {
            int first = content.indexOf('\n');
            int last = content.lastIndexOf("```");
            if (first >= 0 && last > first) {
                return content.substring(first + 1, last).trim();
            }
        }
        return content;
    }
}
