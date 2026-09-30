package com.example.indexing.graph;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.indexing.chunking.DocumentChunk;
import com.example.indexing.common.PipelineException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LlmGraphExtractorTest {

    @Test
    void reportsTimeoutWithoutRetryingItAsInvalidJson() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenThrow(new RuntimeException("request timed out"));
        LlmGraphExtractor extractor = new LlmGraphExtractor(model, new ObjectMapper());

        assertThatThrownBy(() -> extractor.extract(chunk()))
                .isInstanceOf(PipelineException.class)
                .hasMessageContaining("模型请求超时")
                .hasMessageContaining("OPENAI_TIMEOUT");
        verify(model, times(1)).chat(anyString());
    }

    @Test
    void retriesOnlyResponsesThatAreActuallyInvalidJson() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("not-json");
        LlmGraphExtractor extractor = new LlmGraphExtractor(model, new ObjectMapper());

        assertThatThrownBy(() -> extractor.extract(chunk()))
                .isInstanceOf(PipelineException.class)
                .hasMessageContaining("模型返回了无效 JSON");
        verify(model, times(2)).chat(anyString());
    }

    @Test
    void explainsHowToFixTruncatedJsonResponses() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("{\"nodes\":[{\"displayName\":\"未结束");
        LlmGraphExtractor extractor = new LlmGraphExtractor(model, new ObjectMapper());

        assertThatThrownBy(() -> extractor.extract(chunk()))
                .isInstanceOf(PipelineException.class)
                .hasMessageContaining("响应可能被截断")
                .hasMessageContaining("OPENAI_MAX_COMPLETION_TOKENS");
        verify(model, times(2)).chat(anyString());
    }

    private DocumentChunk chunk() {
        return new DocumentChunk(
                "c", "d", "v", 0, "doc.md", "测试文本", "", "", null, null,
                List.of(), 2, Map.of());
    }
}
