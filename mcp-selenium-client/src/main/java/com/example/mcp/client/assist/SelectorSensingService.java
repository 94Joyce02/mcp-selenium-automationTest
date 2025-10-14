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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Uses the running MCP Selenium server to gather live selector hints. Results are cached per URL
 * so repeated prompts for the same page avoid re-opening a sensing browser session.
 */
@Service
public class SelectorSensingService {

    private static final Logger log = LoggerFactory.getLogger(SelectorSensingService.class);

    private final StdioInvoker stdioInvoker;
    private final Map<String, List<Map<String, Object>>> cache = new ConcurrentHashMap<>();

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
        String cacheKey = normalizeUrl(url);
        List<Map<String, Object>> cached = cache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            log.debug("selector sensing cache hit for {}", cacheKey);
            return deepCopy(cached);
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
        gotoAction.put("url", cacheKey);
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
                            List<Map<String, Object>> snapshot = deepCopy(cast);
                            if (!snapshot.isEmpty()) {
                                cache.put(cacheKey, snapshot);
                            }
                            return deepCopy(snapshot);
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

    public void invalidate(String url) {
        if (!StringUtils.hasText(url)) {
            return;
        }
        cache.remove(normalizeUrl(url));
    }

    public void clearCache() {
        cache.clear();
    }

    private List<Map<String, Object>> deepCopy(List<Map<String, Object>> original) {
        List<Map<String, Object>> copy = new ArrayList<>(original.size());
        for (Map<String, Object> entry : original) {
            Map<String, Object> inner = new LinkedHashMap<>();
            entry.forEach((k, v) -> inner.put(k, cloneValue(v)));
            copy.add(inner);
        }
        return copy;
    }

    private Object cloneValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> clone = new LinkedHashMap<>();
            map.forEach((k, v) -> clone.put(String.valueOf(k), cloneValue(v)));
            return clone;
        }
        if (value instanceof List<?> list) {
            List<Object> clone = new ArrayList<>(list.size());
            for (Object item : list) {
                clone.add(cloneValue(item));
            }
            return clone;
        }
        return value;
    }

    private String normalizeUrl(String url) {
        return url.trim();
    }
}
