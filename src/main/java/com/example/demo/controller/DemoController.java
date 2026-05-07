package com.example.demo.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DemoController {

    /** Simple health-like response */
    @GetMapping("/hello")
    public ResponseEntity<Map<String, Object>> hello() {
        return ResponseEntity.ok(Map.of(
            "message", "Hello from Demo App!",
            "status",  "UP",
            "time",    LocalDateTime.now().toString()
        ));
    }

    /** Version info — useful to verify the deployed version */
    @GetMapping("/version")
    public ResponseEntity<Map<String, String>> version() {
        return ResponseEntity.ok(Map.of(
            "app",     "demo-app",
            "version", "1.0.0",
            "env",     System.getenv().getOrDefault("APP_ENV", "DEV")
        ));
    }

    /** Echo endpoint — useful for quick smoke tests */
    @PostMapping("/echo")
    public ResponseEntity<Map<String, Object>> echo(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.ok(Map.of(
            "echo",    body != null ? body : Map.of(),
            "received", LocalDateTime.now().toString()
        ));
    }
}
