
package com.example.mcp.server;

import com.example.mcp.server.proto.RequestEnvelope;
import com.example.mcp.server.proto.ResponseEnvelope;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class RestApiController {
    private final SeleniumServerApplication app;

    public RestApiController(SeleniumServerApplication app) {
        this.app = app;
    }

    @PostMapping("/execute")
    public ResponseEnvelope execute(@RequestBody RequestEnvelope req) {
        return app.handlePublic(req);
    }
}
