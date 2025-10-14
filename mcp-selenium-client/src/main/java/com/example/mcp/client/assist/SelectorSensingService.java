package com.example.mcp.client.assist;

import com.example.mcp.client.transport.InvokerFactory;
import com.example.mcp.client.transport.StdioInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Uses the running MCP Selenium server to gather live selector hints. It opens a short-lived
 * browser session in headless mode, navigates to the target URL, and invokes the new
 * {@code sense_elements} action to obtain relevant DOM elements.
 */
@Service
public class SelectorSensingService {

    private static final Logger log = LoggerFactory.getLogger(SelectorSensingService.class);

    private final StdioInvoker stdioInvoker;

    public SelectorSensingService(InvokerFactory factory) {
        var invoker = factory.get();
        this.stdioInvoker = invoker instanceof StdioInvoker si ? si : null;
    }

    public List<Map<String, Object>> sense(String url, List<String> keywords, int limit) {
        if (stdioInvoker == null) {
            return List.of();
        }
        if (!StringUtils.hasText(url)) {
            return List.of();
        }
        List<String> compactKeywords = Optional.ofNullable(keywords)
                .orElse(List.of())
                .stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        Map<String, Object> senseAction = new LinkedHashMap<>();
        senseAction.put("type", "sense_elements");
        if (!compactKeywords.isEmpty()) {
            senseAction.put("keywords", compactKeywords);
        }
        senseAction.put("limit", limit);
        senseAction.put("note", "auto-sense hints");
        senseAction.put("timeoutMs", 20000);

        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("type", "open_browser");
        open.put("headless", true);
        open.put("note", "sense session");
        actions.add(open);

        Map<String, Object> gotoAction = new LinkedHashMap<>();
        gotoAction.put("type", "goto");
        gotoAction.put("url", url);
        gotoAction.put("note", "sense session");
        actions.add(gotoAction);

        actions.add(senseAction);

        Map<String, Object> quit = new LinkedHashMap<>();
        quit.put("type", "quit");
        quit.put("note", "sense cleanup");
        actions.add(quit);

        try {
            List<Map<String, Object>> steps = stdioInvoker.executeStepwise(actions, true, UUID.randomUUID().toString());
            for (Map<String, Object> step : steps) {
                Object results = step.get("data");
                if (results instanceof Map<?, ?> map) {
                    Object inner = map.get("results");
                    if (inner instanceof Map<?, ?> innerMap) {
                        Object hints = innerMap.get("sense_elements");
                        if (hints instanceof List<?> list) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> cast = (List<Map<String, Object>>) (List<?>) list;
                            return cast;
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("selector sensing failed: {}", e.getMessage());
        }
        return List.of();
    }

    public List<Map<String, Object>> senseFromPrompt(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            return List.of();
        }
        String url = PromptAnalysisUtils.extractPrimaryUrl(prompt);
        List<String> keywords = PromptAnalysisUtils.extractKeywords(prompt);
        return sense(url, keywords, 8);
    }
}
