package com.example.evaluation.dataset;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/evaluation")
public class DatasetController {
    private final DatasetService service;

    public DatasetController(DatasetService service) {
        this.service = service;
    }

    @PostMapping("/datasets/import")
    public Map<String, Object> importDataset(
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam("file") MultipartFile file) {
        return service.importJsonl(name, description, file);
    }

    @GetMapping("/datasets")
    public List<Map<String, Object>> datasets() {
        return service.list();
    }

    @GetMapping("/dataset-revisions/{id}")
    public Map<String, Object> revision(@PathVariable String id) {
        return service.revision(id);
    }
}
