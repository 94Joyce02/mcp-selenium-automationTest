
package com.example.mcp.server.proto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Action {
    private String type;       // open_browser, goto, click, type, find_text, screenshot, close, quit, wait, wait_for_selector, scroll_by, scroll_to, key_press, switch_to_frame, switch_to_default, download_link, get_title, get_current_url, sense_elements
    private String selector;   // css or xpath
    private String text;       // for type/find_text/key_press (e.g., ENTER)
    private String url;        // for goto
    private String by;         // css|xpath (optional)
    private java.util.List<String> keywords; // for sense_elements
    private Integer limit;     // optional limit for sense_elements
    private String scope;      // optional hint for sensing scope

    // open_browser options
    private Boolean headless;
    private String downloadDir;

    // wait parameters
    private Integer timeoutMs;
    private Integer x;
    private Integer y;
    private Integer frameIndex;
    private String note;

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
    public java.util.List<String> getKeywords() { return keywords; }
    public void setKeywords(java.util.List<String> keywords) { this.keywords = keywords; }
    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

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
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
