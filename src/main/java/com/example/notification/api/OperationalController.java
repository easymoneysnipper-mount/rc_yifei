package com.example.notification.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class OperationalController {

    private final JdbcTemplate jdbcTemplate;

    public OperationalController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/livez")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/readyz")
    public Map<String, String> ready() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "UP");
    }
}
