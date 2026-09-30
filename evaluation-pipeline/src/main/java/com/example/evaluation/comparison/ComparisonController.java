package com.example.evaluation.comparison;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluation/comparisons")
public class ComparisonController {
    private final ComparisonService service;

    public ComparisonController(ComparisonService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> compare(
            @RequestParam String baselineRunId, @RequestParam String candidateRunId) {
        return service.compare(baselineRunId, candidateRunId);
    }
}
