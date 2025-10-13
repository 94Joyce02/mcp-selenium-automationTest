
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
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;

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
            for (Action a : req.getActions()) {
                idx++;
                String type = a.getType() == null ? "" : a.getType();
                log.info("[Server] ← client={} action#{} {}", clientId, idx, type);

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
            ResponseEnvelope ok = ResponseEnvelope.ok("All actions executed");
            ok.getData().put("results", results);
            return ok;
        } catch (Exception e) {
            return ResponseEnvelope.error("Execution failed: " + e.getMessage());
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
}
