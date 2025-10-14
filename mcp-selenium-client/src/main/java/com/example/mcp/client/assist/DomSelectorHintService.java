package com.example.mcp.client.assist;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Lightweight DOM crawler that fetches the target page and produces high-confidence selector hints.
 * Fetching happens on the server so we avoid CORS issues in the browser. The output is deliberately
 * compact so it can be stitched into the LLM prompt.
 */
@Service
public class DomSelectorHintService {

    private static final Logger log = LoggerFactory.getLogger(DomSelectorHintService.class);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 13_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121 Safari/537.36";

    public List<SelectorHint> suggest(String url, List<String> keywords, int limit) {
        if (!StringUtils.hasText(url) || keywords == null || keywords.isEmpty()) {
            return List.of();
        }
        String normalizedUrl = normalize(url);
        if (normalizedUrl == null) {
            return List.of();
        }
        try {
            Document doc = Jsoup.connect(normalizedUrl)
                    .userAgent(USER_AGENT)
                    .timeout((int) Duration.ofSeconds(10).toMillis())
                    .get();
            Elements candidates = doc.select("input, textarea, select, button, a, [role=button], [role=search]");
            List<SelectorHint> scored = new ArrayList<>();
            for (Element el : candidates) {
                if (isSkippable(el)) {
                    continue;
                }
                SelectorHint hint = buildHint(el, keywords);
                if (hint != null && hint.score() > 0.15) {
                    scored.add(hint);
                }
            }
            return scored.stream()
                    .sorted(Comparator.comparingDouble(SelectorHint::score).reversed())
                    .limit(limit)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("Selector hint fetch failed for {}: {}", url, e.getMessage());
            return List.of();
        }
    }

    private static boolean isSkippable(Element el) {
        String type = el.attr("type");
        return "hidden".equalsIgnoreCase(type) || el.hasAttr("hidden") || el.attr("style").contains("display:none");
    }

    private static SelectorHint buildHint(Element el, List<String> keywords) {
        Map<String, String> attributes = extractAttributes(el);
        String haystack = (el.tagName() + " " + String.join(" ", attributes.values()))
                .toLowerCase(Locale.ROOT);
        double score = 0.0;
        for (String keyword : keywords) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            String kw = keyword.toLowerCase(Locale.ROOT);
            if (haystack.contains(kw)) {
                score += 1.0;
            } else {
                for (String token : kw.split("\\s+")) {
                    if (haystack.contains(token)) {
                        score += 0.3;
                    }
                }
            }
        }
        if (!StringUtils.hasText(attributes.get("selector"))) {
            attributes.put("selector", buildCssSelector(el));
        }
        if (!StringUtils.hasText(attributes.get("selector"))) {
            return null;
        }
        // Bonus for strong selectors
        if (attributes.get("selector").startsWith("#")) {
            score += 0.4;
        } else if (attributes.get("selector").contains("[name=")) {
            score += 0.2;
        }
        return new SelectorHint(
                attributes.get("selector"),
                el.tagName(),
                score,
                attributes
        );
    }

    private static Map<String, String> extractAttributes(Element el) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("tag", el.tagName());
        copyAttr(el, attrs, "id");
        copyAttr(el, attrs, "name");
        copyAttr(el, attrs, "type");
        copyAttr(el, attrs, "placeholder");
        copyAttr(el, attrs, "aria-label");
        copyAttr(el, attrs, "data-testid");
        String text = el.ownText();
        if (!StringUtils.hasText(text)) {
            text = el.attr("value");
        }
        if (StringUtils.hasText(text)) {
            attrs.put("text", text.strip());
        }
        return attrs;
    }

    private static void copyAttr(Element el, Map<String, String> dest, String attr) {
        String value = el.attr(attr);
        if (StringUtils.hasText(value)) {
            dest.put(attr, value);
        }
    }

    private static String buildCssSelector(Element el) {
        String id = el.id();
        if (StringUtils.hasText(id)) {
            return "#" + cssEscape(id);
        }
        String name = el.attr("name");
        if (StringUtils.hasText(name)) {
            return el.tagName() + "[name='" + cssEscape(name) + "']";
        }
        String dataTestId = el.attr("data-testid");
        if (StringUtils.hasText(dataTestId)) {
            return "[data-testid='" + cssEscape(dataTestId) + "']";
        }
        String ariaLabel = el.attr("aria-label");
        if (StringUtils.hasText(ariaLabel)) {
            return el.tagName() + "[aria-label='" + cssEscape(ariaLabel) + "']";
        }
        String placeholder = el.attr("placeholder");
        if (StringUtils.hasText(placeholder)) {
            return el.tagName() + "[placeholder='" + cssEscape(placeholder) + "']";
        }
        String type = el.attr("type");
        if (StringUtils.hasText(type)) {
            return el.tagName() + "[type='" + cssEscape(type) + "']";
        }
        return null;
    }

    private static String cssEscape(String raw) {
        return raw.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String normalize(String url) {
        try {
            URI uri = new URI(url.trim());
            if (uri.getScheme() == null) {
                uri = new URI("https://" + url.trim());
            }
            return uri.toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    public record SelectorHint(String selector,
                               String tag,
                               double score,
                               Map<String, String> attributes) {

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("selector", selector);
            map.put("tag", tag);
            map.put("score", score);
            map.put("attributes", attributes);
            return map;
        }
    }
}
