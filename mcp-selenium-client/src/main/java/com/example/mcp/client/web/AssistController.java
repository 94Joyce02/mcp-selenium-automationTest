package com.example.mcp.client.web;

import com.example.mcp.client.assist.DomSelectorHintService;
import com.example.mcp.client.assist.DomSelectorHintService.SelectorHint;
import com.example.mcp.client.assist.PromptAnalysisUtils;
import com.example.mcp.client.assist.SelectorSensingService;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/assist")
public class AssistController {

    private final DomSelectorHintService hintService;
    private final SelectorSensingService sensingService;

    public AssistController(DomSelectorHintService hintService,
                            SelectorSensingService sensingService) {
        this.hintService = hintService;
        this.sensingService = sensingService;
    }

    @PostMapping("/selector-hints")
    public Map<String, Object> selectorHints(@RequestBody Map<String, Object> body) {
        String prompt = stringValue(body.get("prompt"));
        String url = stringValue(body.get("url"));
        if (!StringUtils.hasText(url)) {
            url = PromptAnalysisUtils.extractPrimaryUrl(prompt);
        }
        List<String> keywords = extractKeywords(body, prompt);
        List<Map<String, Object>> hints = sensingService.sense(url, keywords, 8);
        String source = "runtime";
        if (hints.isEmpty()) {
            hints = hintService.suggest(url, keywords, 8).stream()
                    .map(SelectorHint::toMap)
                    .collect(Collectors.toList());
            source = "static";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        result.put("keywords", keywords);
        result.put("hints", hints);
        result.put("hasHints", !hints.isEmpty());
        result.put("source", source);
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractKeywords(Map<String, Object> body, String prompt) {
        Object raw = body.get("keywords");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                    .map(this::stringValue)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
        }
        return PromptAnalysisUtils.extractKeywords(prompt);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
