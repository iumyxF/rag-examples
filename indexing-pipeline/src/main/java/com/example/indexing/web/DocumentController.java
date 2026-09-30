package com.example.indexing.web;

import com.example.indexing.config.RagProperties;
import com.example.indexing.document.DocumentIndexingService;
import com.example.indexing.graph.GraphRepository;
import com.example.indexing.visualization.GraphViewBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentIndexingService service;
    private final GraphRepository graph;
    private final GraphViewBuilder view;
    private final RagProperties properties;

    public DocumentController(
            DocumentIndexingService service,
            GraphRepository graph,
            GraphViewBuilder view,
            RagProperties properties) {
        this.service = service;
        this.graph = graph;
        this.view = view;
        this.properties = properties;
    }

    @PostMapping
    public Object upload(@RequestParam("file") MultipartFile file) {
        return service.upload(file);
    }

    @GetMapping
    public Object list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Object get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/{id}/pipeline/{step}")
    public Object execute(@PathVariable String id, @PathVariable String step) {
        return service.execute(id, step);
    }

    @GetMapping("/{id}/pipeline/{step}/preview")
    public Object preview(
            @PathVariable String id,
            @PathVariable String step,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return service.preview(id, step, offset, limit);
    }

    @DeleteMapping("/{id}/working-version")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWorkingVersion(@PathVariable String id) {
        service.deleteWorkingVersion(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    @GetMapping("/{id}/graph")
    public Object graph(@PathVariable String id) {
        var document = service.get(id);
        if (document.document().activeVersionId() == null) {
            throw new IllegalArgumentException("文档尚未完成索引");
        }
        var snapshot =
                graph.documentGraph(
                        id,
                        document.document().activeVersionId(),
                        properties.graph().maxNodes(),
                        properties.graph().maxEdges());
        return java.util.Map.of("graph", snapshot, "mermaid", view.toMermaid(snapshot));
    }
}
