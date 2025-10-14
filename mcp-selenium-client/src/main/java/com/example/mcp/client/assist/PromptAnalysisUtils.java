package com.example.mcp.client.assist;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PromptAnalysisUtils {

    private static final Pattern URL_PATTERN = Pattern.compile("(https?://[^\\s,;]+)");
    private static final Pattern WWW_PATTERN = Pattern.compile("www\\.[^\\s,;]+");
    private static final Pattern QUOTED_PATTERN = Pattern.compile("[\"“”']([^\"“”']{1,120})[\"“”']");

    private PromptAnalysisUtils() {
    }

    public static String extractPrimaryUrl(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        if (matcher.find()) {
            return trimTrailingPunctuation(matcher.group(1));
        }
        matcher = WWW_PATTERN.matcher(text);
        if (matcher.find()) {
            return "https://" + trimTrailingPunctuation(matcher.group());
        }
        return null;
    }

    public static List<String> extractKeywords(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        Matcher m = QUOTED_PATTERN.matcher(text);
        while (m.find()) {
            String candidate = m.group(1).trim();
            if (candidate.length() >= 2 && candidate.length() <= 80) {
                out.add(candidate);
            }
        }
        // fallbacks around verbs
        addKeywordAfter(text, out, "search for");
        addKeywordAfter(text, out, "look for");
        addKeywordAfter(text, out, "find");
        addKeywordAfter(text, out, "type");
        if (out.isEmpty()) {
            String[] parts = text.split("[\\r\\n]+");
            for (String part : parts) {
                if (part.toLowerCase().contains("search")) {
                    out.add(part.trim());
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static void addKeywordAfter(String text, Set<String> out, String key) {
        int idx = text.toLowerCase().indexOf(key);
        if (idx >= 0) {
            int start = idx + key.length();
            String fragment = text.substring(start).trim();
            if (!fragment.isEmpty()) {
                String[] tokens = fragment.split("[\\r\\n,.]");
                if (tokens.length > 0) {
                    String candidate = tokens[0].trim();
                    if (candidate.length() >= 2) {
                        out.add(candidate);
                    }
                }
            }
        }
    }

    private static String trimTrailingPunctuation(String input) {
        return input.replaceAll("[)\\].,;]+$", "");
    }
}
