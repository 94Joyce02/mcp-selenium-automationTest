
package com.example.mcp.server;

import com.example.mcp.server.proto.Action;
import com.example.mcp.server.proto.RequestEnvelope;
import com.example.mcp.server.proto.ResponseEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bonigarcia.wdm.WebDriverManager;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.util.StringUtils;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@SpringBootApplication
public class SeleniumServerApplication implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(SeleniumServerApplication.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private WebDriver driver;
    private Path downloadDir = Path.of("downloads");

    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(SeleniumServerApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        WebDriverManager.chromedriver().setup();
        log.info("MCP Selenium Server started. Waiting for STDIN JSON lines...");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(System.out))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    RequestEnvelope req = mapper.readValue(line, RequestEnvelope.class);
                    ResponseEnvelope resp = handle(req);
                    String out = mapper.writeValueAsString(resp);
                    bw.write(out);
                    bw.newLine();
                    bw.flush();
                } catch (Exception e) {
                    log.error("Process error", e);
                    ResponseEnvelope err = ResponseEnvelope.error(e.getMessage());
                    String out = mapper.writeValueAsString(err);
                    bw.write(out);
                    bw.newLine();
                    bw.flush();
                }
            }
        }
    }

    // exposed for REST controller
    public ResponseEnvelope handlePublic(RequestEnvelope req) {
        return handle(req);
    }

    private ResponseEnvelope handle(RequestEnvelope req) {
        String clientId = req.getClientId() == null ? "unknown-client" : req.getClientId();
        if (!"execute".equalsIgnoreCase(req.getMethod())) {
            return ResponseEnvelope.error("Unsupported method: " + req.getMethod());
        }
        if (req.getActions() == null || req.getActions().isEmpty()) {
            return ResponseEnvelope.error("No actions specified");
        }
        try {
            Map<String, Object> results = new LinkedHashMap<>();
            int idx = 0;
            boolean sessionMode = req.getSessionId() != null && !req.getSessionId().isBlank();
            boolean sessionDone = Boolean.TRUE.equals(req.getSessionDone());
            for (Action a : req.getActions()) {
                idx++;
                String type = a.getType() == null ? "" : a.getType();
                log.info("[Server] ← client={} action#{} {}", clientId, idx, type);
                if (a.getNote() != null && !a.getNote().isBlank()) {
                    log.info("          note: {}", a.getNote());
                }

                switch (type) {
                    case "open_browser" -> {
                        if (driver == null) {
                            ChromeOptions options = new ChromeOptions();
                            if (Boolean.TRUE.equals(a.getHeadless())) {
                                options.addArguments("--headless=new");
                            }
                            options.addArguments("--remote-allow-origins=*");
                            if (a.getDownloadDir() != null && !a.getDownloadDir().isBlank()) {
                                downloadDir = Paths.get(a.getDownloadDir());
                            }
                            Files.createDirectories(downloadDir);
                            Map<String, Object> prefs = new HashMap<>();
                            prefs.put("download.default_directory", downloadDir.toAbsolutePath().toString());
                            prefs.put("download.prompt_for_download", false);
                            options.setExperimentalOption("prefs", prefs);
                            driver = new ChromeDriver(options);
                        }
                        results.put("open_browser", "ok");
                    }
                    case "set_download_dir" -> {
                        if (a.getDownloadDir() != null) {
                            downloadDir = Paths.get(a.getDownloadDir());
                            Files.createDirectories(downloadDir);
                            results.put("set_download_dir", downloadDir.toAbsolutePath().toString());
                        } else {
                            results.put("set_download_dir", "ignored (null)");
                        }
                    }
                    case "goto" -> {
                        ensureDriver();
                        driver.get(a.getUrl());
                        results.put("goto", driver.getCurrentUrl());
                    }
                    case "click" -> {
                        ensureDriver();
                        WebElement el = findElement(a);
                        el.click();
                        results.put("click", "ok");
                    }
                    case "type" -> {
                        ensureDriver();
                        WebElement el = findElement(a);
                        el.clear();
                        el.sendKeys(a.getText() == null ? "" : a.getText());
                        results.put("type", "ok");
                    }
                    case "key_press" -> {
                        ensureDriver();
                        Keys k = parseKey(a.getText());
                        if (a.getSelector() != null) {
                            WebElement el = findElement(a);
                            el.sendKeys(k);
                        } else {
                            new Actions(driver).sendKeys(k).perform();
                        }
                        results.put("key_press", a.getText());
                    }
                    case "find_text" -> {
                        ensureDriver();
                        String page = driver.getPageSource();
                        boolean found = a.getText() != null && page.contains(a.getText());
                        results.put("find_text", found);
                    }
                    case "wait" -> {
                        int ms = a.getTimeoutMs() == null ? 1000 : a.getTimeoutMs();
                        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
                        results.put("wait", ms);
                    }
                    case "wait_for_selector" -> {
                        ensureDriver();
                        int ms = a.getTimeoutMs() == null ? 10000 : a.getTimeoutMs();
                        WebDriverWait wait = new WebDriverWait(driver, Duration.ofMillis(ms));
                        if ("xpath".equals(a.getBy()) || (a.getBy() == null && a.getSelector() != null && a.getSelector().startsWith("//"))) {
                            wait.until(ExpectedConditions.visibilityOfElementLocated(By.xpath(a.getSelector())));
                        } else {
                            wait.until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(a.getSelector())));
                        }
                        results.put("wait_for_selector", "ok");
                    }
                    case "scroll_by" -> {
                        ensureDriver();
                        int dx = a.getX() == null ? 0 : a.getX();
                        int dy = a.getY() == null ? 300 : a.getY();
                        ((JavascriptExecutor)driver).executeScript("window.scrollBy(arguments[0], arguments[1]);", dx, dy);
                        results.put("scroll_by", List.of(dx, dy));
                    }
                    case "scroll_to" -> {
                        ensureDriver();
                        WebElement el = findElement(a);
                        ((JavascriptExecutor)driver).executeScript("arguments[0].scrollIntoView({behavior:'smooth',block:'center'});", el);
                        results.put("scroll_to", "ok");
                    }
                    case "switch_to_frame" -> {
                        ensureDriver();
                        if (a.getFrameIndex() != null) {
                            driver.switchTo().frame(a.getFrameIndex());
                        } else if (a.getSelector() != null) {
                            WebElement frame = findElement(a);
                            driver.switchTo().frame(frame);
                        } else {
                            throw new IllegalArgumentException("switch_to_frame requires frameIndex or selector");
                        }
                        results.put("switch_to_frame", "ok");
                    }
                    case "switch_to_default" -> {
                        ensureDriver();
                        driver.switchTo().defaultContent();
                        results.put("switch_to_default", "ok");
                    }
                    case "sense_elements" -> {
                        ensureDriver();
                        List<Map<String, Object>> hints = senseElements(a);
                        results.put("sense_elements", hints);
                    }
                    case "download_link" -> {
                        ensureDriver();
                        long beforeCount = filesCount(downloadDir);
                        WebElement el = findElement(a);
                        el.click();
                        // wait up to 20s for a new file to appear
                        Path newFile = waitForNewFile(downloadDir, beforeCount, 20_000);
                        results.put("download_link", newFile == null ? "unknown" : newFile.toAbsolutePath().toString());
                    }
                    case "get_title" -> {
                        ensureDriver();
                        results.put("get_title", driver.getTitle());
                    }
                    case "get_current_url" -> {
                        ensureDriver();
                        results.put("get_current_url", driver.getCurrentUrl());
                    }
                    case "screenshot" -> {
                        ensureDriver();
                        Path dir = Path.of("screens");
                        Files.createDirectories(dir);
                        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
                        Path file = dir.resolve("shot_" + ts + ".png");
                        TakesScreenshot tsd = (TakesScreenshot) driver;
                        byte[] bytes = tsd.getScreenshotAs(OutputType.BYTES);
                        Files.write(file, bytes);
                        results.put("screenshot", file.toAbsolutePath().toString());
                    }
                    case "close" -> {
                        if (driver != null) {
                            driver.close();
                            driver = null;
                        }
                        results.put("close", "ok");
                    }
                    case "quit" -> {
                        if (driver != null) {
                            driver.quit();
                            driver = null;
                        }
                        results.put("quit", "ok");
                    }
                    default -> throw new IllegalArgumentException("Unknown action: " + type);
                }
            }
            boolean closed = false;
            if (!sessionMode || sessionDone) {
                closed = cleanupDriver("case completed");
            }
            ResponseEnvelope ok = ResponseEnvelope.ok("All actions executed");
            ok.getData().put("results", results);
            ok.getData().put("browserClosed", closed);
            return ok;
        } catch (Exception e) {
            boolean closed = cleanupDriver("error encountered");
            ResponseEnvelope err = ResponseEnvelope.error("Execution failed: " + e.getMessage());
            err.getData().put("browserClosed", closed);
            return err;
        }
    }

    private void ensureDriver() {
        if (driver == null) throw new IllegalStateException("Browser not opened. Call open_browser first.");
    }

    private WebElement findElement(Action a) {
        String by = a.getBy();
        String selector = a.getSelector();
        if (selector == null) throw new IllegalArgumentException("selector required");
        if (by == null) {
            if (selector.startsWith("//")) {
                by = "xpath";
            } else {
                by = "css";
            }
        }
        return switch (by) {
            case "xpath" -> driver.findElement(By.xpath(selector));
            case "css" -> driver.findElement(By.cssSelector(selector));
            default -> throw new IllegalArgumentException("Unsupported locator 'by': " + by);
        };
    }

    private List<Map<String, Object>> senseElements(Action action) {
        List<String> keywords = action.getKeywords();
        if ((keywords == null || keywords.isEmpty()) && action.getText() != null) {
            keywords = List.of(action.getText());
        }
        if (keywords == null) {
            keywords = Collections.emptyList();
        }
        int limit = action.getLimit() != null && action.getLimit() > 0 ? Math.min(action.getLimit(), 20) : 8;
        String scopeSelector = scopeToSelector(action.getScope());
        List<WebElement> candidates = driver.findElements(By.cssSelector(scopeSelector));
        List<Map<String, Object>> sensed = new ArrayList<>();
        for (WebElement candidate : candidates) {
            try {
                Map<String, Object> info = buildSenseEntry(candidate, keywords);
                if (info != null) {
                    sensed.add(info);
                }
            } catch (StaleElementReferenceException ignored) {
                // element disappeared between listing and inspection; skip
            } catch (JavascriptException e) {
                log.debug("senseElements JS error: {}", e.getMessage());
            }
        }
        sensed.sort((a, b) -> Double.compare(asDouble(b.get("score")), asDouble(a.get("score"))));
        if (sensed.size() > limit) {
            return new ArrayList<>(sensed.subList(0, limit));
        }
        return sensed;
    }

    private Map<String, Object> buildSenseEntry(WebElement element, List<String> keywords) {
        if (element == null) {
            return null;
        }
        try {
            if (!element.isDisplayed()) {
                return null;
            }
        } catch (StaleElementReferenceException ignored) {
            return null;
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        String tag = safeLower(element.getTagName());
        attrs.put("tag", tag);
        copyAttr(element, attrs, "id");
        copyAttr(element, attrs, "name");
        copyAttr(element, attrs, "type");
        copyAttr(element, attrs, "placeholder");
        copyAttr(element, attrs, "aria-label");
        copyAttr(element, attrs, "data-testid");
        copyAttr(element, attrs, "value");
        String text = "";
        try {
            text = element.getText();
        } catch (Exception ignored) {
        }
        if (!StringUtils.hasText(text)) {
            text = element.getAttribute("value");
        }
        if (StringUtils.hasText(text)) {
            attrs.put("text", text.trim());
        }
        String selector = buildCssSelector(element, attrs);
        if (!StringUtils.hasText(selector)) {
            return null;
        }
        double score = computeScore(tag, attrs, keywords);
        if (!keywords.isEmpty() && score <= 0.0) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("selector", selector);
        map.put("tag", tag);
        map.put("score", score);
        map.put("attributes", attrs);
        Rectangle rect = element.getRect();
        Map<String, Object> rectMap = new LinkedHashMap<>();
        rectMap.put("x", rect.getX());
        rectMap.put("y", rect.getY());
        rectMap.put("width", rect.getWidth());
        rectMap.put("height", rect.getHeight());
        map.put("rect", rectMap);
        return map;
    }

    private String scopeToSelector(String scope) {
        if (!StringUtils.hasText(scope)) {
            return "input, textarea, select, button, a, [role=button], [role=link], [role=search]";
        }
        return switch (scope.toLowerCase(Locale.ROOT)) {
            case "forms", "inputs" ->
                    "input, textarea, select, button, [role=textbox]";
            case "links" ->
                    "a, [role=link]";
            case "actions", "buttons" ->
                    "button, [role=button], input[type='submit'], input[type='button']";
            default ->
                    "input, textarea, select, button, a, [role=button], [role=link], [role=search]";
        };
    }

    private void copyAttr(WebElement element, Map<String, String> dest, String name) {
        try {
            String value = element.getAttribute(name);
            if (StringUtils.hasText(value)) {
                dest.put(name, value.trim());
            }
        } catch (Exception ignored) {
        }
    }

    private double computeScore(String tag, Map<String, String> attrs, List<String> keywords) {
        StringBuilder sb = new StringBuilder();
        sb.append(tag);
        for (String val : attrs.values()) {
            sb.append(' ').append(val);
        }
        String haystack = sb.toString().toLowerCase(Locale.ROOT);
        double score = keywords.isEmpty() ? 0.2 : 0.0;
        for (String keyword : keywords) {
            if (!StringUtils.hasText(keyword)) continue;
            String kw = keyword.toLowerCase(Locale.ROOT);
            if (haystack.contains(kw)) {
                score += 1.0;
            } else {
                for (String token : kw.split("\\s+")) {
                    if (token.length() >= 2 && haystack.contains(token)) {
                        score += 0.3;
                    }
                }
            }
        }
        if (attrs.containsKey("id")) {
            score += 0.5;
        }
        if (attrs.containsKey("name")) {
            score += 0.2;
        }
        if (attrs.containsKey("placeholder")) {
            score += 0.2;
        }
        if (attrs.containsKey("aria-label")) {
            score += 0.25;
        }
        if ("input".equals(tag) || "textarea".equals(tag)) {
            score += 0.1;
        }
        return score;
    }

    private String buildCssSelector(WebElement element, Map<String, String> attrs) {
        String id = attrs.get("id");
        if (StringUtils.hasText(id)) {
            return "#" + cssEscape(id);
        }
        String dataTestId = attrs.get("data-testid");
        if (StringUtils.hasText(dataTestId)) {
            return "[data-testid='" + cssEscape(dataTestId) + "']";
        }
        String name = attrs.get("name");
        if (StringUtils.hasText(name)) {
            return element.getTagName().toLowerCase(Locale.ROOT) + "[name='" + cssEscape(name) + "']";
        }
        String ariaLabel = attrs.get("aria-label");
        if (StringUtils.hasText(ariaLabel)) {
            return element.getTagName().toLowerCase(Locale.ROOT) + "[aria-label='" + cssEscape(ariaLabel) + "']";
        }
        String placeholder = attrs.get("placeholder");
        if (StringUtils.hasText(placeholder)) {
            return element.getTagName().toLowerCase(Locale.ROOT) + "[placeholder='" + cssEscape(placeholder) + "']";
        }
        String value = attrs.get("value");
        if (StringUtils.hasText(value) && value.length() <= 40) {
            return element.getTagName().toLowerCase(Locale.ROOT) + "[value='" + cssEscape(value) + "']";
        }
        String script = """
                function cssPath(el) {
                  if (!(el instanceof Element)) return null;
                  const path = [];
                  while (el && el.nodeType === Node.ELEMENT_NODE && path.length < 6) {
                    let selector = el.nodeName.toLowerCase();
                    if (el.id) {
                      selector += "#" + CSS.escape(el.id);
                      path.unshift(selector);
                      break;
                    }
                    let sib = el;
                    let nth = 1;
                    while ((sib = sib.previousElementSibling) != null) {
                      if (sib.nodeName === el.nodeName) {
                        nth++;
                      }
                    }
                    selector += ":nth-of-type(" + nth + ")";
                    path.unshift(selector);
                    el = el.parentNode;
                  }
                  return path.join(" > ");
                }
                return cssPath(arguments[0]);
                """;
        Object evaluated = ((JavascriptExecutor) driver).executeScript(script, element);
        if (evaluated instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private double asDouble(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0.0;
    }

    private String safeLower(String in) {
        return in == null ? "" : in.toLowerCase(Locale.ROOT);
    }

    private String cssEscape(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"");
    }

    private long filesCount(Path dir) throws IOException {
        if (!Files.exists(dir)) return 0;
        try (var s = Files.list(dir)) {
            return s.count();
        }
    }

    private Path waitForNewFile(Path dir, long before, long timeoutMs) throws IOException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            long now = filesCount(dir);
            if (now > before) {
                // return the newest file
                try (var s = Files.list(dir)) {
                    return s.max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
                }
            }
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        }
        return null;
    }

    private Keys parseKey(String name) {
        if (name == null) return Keys.ENTER;
        try {
            return Keys.valueOf(name.toUpperCase());
        } catch (Exception e) {
            return Keys.ENTER;
        }
    }

    private boolean cleanupDriver(String reason) {
        if (driver != null) {
            try {
                log.info("Closing browser session ({})", reason);
                driver.quit();
            } catch (Exception ex) {
                log.warn("Error while closing browser: {}", ex.getMessage());
            } finally {
                driver = null;
            }
            return true;
        }
        return false;
    }
}
