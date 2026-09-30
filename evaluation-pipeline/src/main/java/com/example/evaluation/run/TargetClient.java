package com.example.evaluation.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class TargetClient {
    private final ObjectMapper mapper;
    private final RestClient.Builder builder;

    public TargetClient(
            ObjectMapper mapper,
            RestClient.Builder builder,
            @Value("${evaluation.target-timeout:120s}") Duration timeout) {
        this.mapper = mapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.builder = builder.requestFactory(requestFactory);
    }

    public JsonNode query(
            String baseUrl, String question, String documentId, boolean generateAnswer) {
        URI base = validateBaseUrl(baseUrl);
        JsonNode response = builder.baseUrl(base.toString()).build()
                .post()
                .uri("/api/internal/evaluation/v1/query")
                .body(Map.of(
                        "question", question,
                        "documentIds", List.of(documentId),
                        "generateAnswer", generateAnswer))
                .retrieve()
                .body(JsonNode.class);
        if (response == null || !response.isObject()) {
            throw new IllegalStateException("被测服务返回了空响应");
        }
        return response;
    }

    public URI validateBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("目标地址不能为空");
        }
        try {
            URI uri = URI.create(baseUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("目标地址必须是有效的 HTTP(S) URL");
            }
            String normalized = baseUrl.endsWith("/")
                    ? baseUrl.substring(0, baseUrl.length() - 1)
                    : baseUrl;
            return URI.create(normalized);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("目标地址无效: " + error.getMessage(), error);
        }
    }
}
