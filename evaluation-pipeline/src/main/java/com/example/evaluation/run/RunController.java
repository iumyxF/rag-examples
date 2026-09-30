package com.example.evaluation.run;

import com.example.evaluation.run.RunService.CreateRun;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluation/runs")
public class RunController {
    private final RunService service;

    public RunController(RunService service) {
        this.service = service;
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody CreateRun request) {
        return service.create(request);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/{id}/cases")
    public List<Map<String, Object>> cases(@PathVariable String id) {
        return service.results(id);
    }

    @GetMapping("/{id}/cases/{caseId}")
    public Map<String, Object> caseResult(@PathVariable String id, @PathVariable String caseId) {
        return service.result(id, caseId);
    }

    @GetMapping("/{id}/failures/export")
    public ResponseEntity<byte[]> export(@PathVariable String id) {
        byte[] body = service.failureExport(id).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("evaluation-failures-" + id + ".jsonl", StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(body);
    }
}
