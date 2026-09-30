package com.example.indexing;

import com.example.indexing.config.RagProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan({"com.example.indexing.document.persistence", "com.example.indexing.graph.persistence"})
@EnableConfigurationProperties(RagProperties.class)
public class IndexingPipelineApplication {

    public static void main(String[] args) {
        SpringApplication.run(IndexingPipelineApplication.class, args);
    }
}
