package com.example.indexing.web;

import com.example.indexing.rag.pipeline.RagPipelineService;
import jakarta.validation.Valid;

import java.util.List;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag")
public class RagController {
    private final RagPipelineService pipeline;

    public RagController(RagPipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping("/query")
    public Object query(@Valid @RequestBody RagQueryRequest request) {
        return pipeline.query(
                request.question(), request.documentIds() == null ? List.of() : request.documentIds());
    }
}
