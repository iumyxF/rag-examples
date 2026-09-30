package com.example.evaluation.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class TargetClientTest {
    @Test
    void callsVersionedEvaluationEndpoint() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"runtimeConfiguration\":{},\"retrieval\":{}}"));
            server.start();
            TargetClient client = new TargetClient(
                    new ObjectMapper(), RestClient.builder(), Duration.ofSeconds(2));

            var response = client.query(server.url("/").toString(), "问题", "doc-1", false);
            var request = server.takeRequest();

            assertThat(request.getPath()).isEqualTo("/api/internal/evaluation/v1/query");
            assertThat(request.getBody().readUtf8()).contains("doc-1", "generateAnswer");
            assertThat(response.path("retrieval").isObject()).isTrue();
        }
    }
}
