
package com.example.mcp.server.proto;

public class Action {
    private String type;       // open_browser, goto, click, type, find_text, screenshot, close, quit, wait, wait_for_selector, scroll_by, scroll_to, key_press, switch_to_frame, switch_to_default, download_link, get_title, get_current_url
    private String selector;   // css or xpath
    private String text;       // for type/find_text/key_press (e.g., ENTER)
    private String url;        // for goto
    private String by;         // css|xpath (optional)

    // open_browser options
    private Boolean headless;
    private String downloadDir;

    // wait parameters
    private Integer timeoutMs;
    private Integer x;
    private Integer y;
    private Integer frameIndex;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSelector() { return selector; }
    public void setSelector(String selector) { this.selector = selector; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getBy() { return by; }
    public void setBy(String by) { this.by = by; }

    public Boolean getHeadless() { return headless; }
    public void setHeadless(Boolean headless) { this.headless = headless; }
    public String getDownloadDir() { return downloadDir; }
    public void setDownloadDir(String downloadDir) { this.downloadDir = downloadDir; }

    public Integer getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }
    public Integer getX() { return x; }
    public void setX(Integer x) { this.x = x; }
    public Integer getY() { return y; }
    public void setY(Integer y) { this.y = y; }
    public Integer getFrameIndex() { return frameIndex; }
    public void setFrameIndex(Integer frameIndex) { this.frameIndex = frameIndex; }
}
