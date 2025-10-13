
package com.example.mcp.client.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebPage {
    @GetMapping("/")
    public String index() {
        return "index";
    }
}
