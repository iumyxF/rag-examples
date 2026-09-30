package com.example.evaluation.metric;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MetricEngineTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MetricEngine engine = new MetricEngine(mapper);

    @Test
    void calculatesStageCitationAndGraphMetricsFromEvidenceGroups() throws Exception {
        JsonNode evaluationCase = mapper.readTree("""
                {
                  "evidenceGroups":[
                    {"alternatives":[{"sourceBlockIds":["block-a"],"quoteText":"安装高度为 2 米","pageStart":4,"pageEnd":4}]},
                    {"alternatives":[{"quoteText":"必须远离强电设备","sectionPath":"注意事项"}]}
                  ],
                  "expectedGraph":{
                    "requiredEntities":[{"canonicalName":"AP"}],
                    "requiredRelations":[{"sourceEntity":"AP","relationType":"安装于","targetEntity":"墙面"}]
                  }
                }
                """);
        JsonNode response = mapper.readTree("""
                {
                  "retrieval":{
                    "bm25":[{"sourceBlockIds":["block-a"],"pageStart":4,"pageEnd":4,"excerpt":"安装高度为 2 米"}],
                    "vector":[],"graph":[],"fused":[],
                    "reranked":[
                      {"sourceBlockIds":["block-a"],"pageStart":4,"pageEnd":4,"excerpt":"安装高度为 2 米"},
                      {"sourceBlockIds":[],"sectionPath":"注意事项","excerpt":"必须远离强电设备"}
                    ]
                  },
                  "contextCitations":[
                    {"id":1,"sourceBlockIds":["block-a"],"pageStart":4,"pageEnd":4,"excerpt":"安装高度为 2 米"}
                  ],
                  "usedCitationIds":[1],
                  "graphTrace":{
                    "entities":[{"canonicalName":"ap"},{"canonicalName":"墙面"}],
                    "relations":[{"sourceEntity":"AP","relationType":"安装于","targetEntity":"墙面"}]
                  }
                }
                """);

        var result = engine.calculate(evaluationCase, response, true);

        assertThat(result.retrieval().path("reranked").path("recallAt1").asDouble()).isEqualTo(0.5);
        assertThat(result.retrieval().path("reranked").path("recallAt3").asDouble()).isEqualTo(1.0);
        assertThat(result.retrieval().path("reranked").path("mrr").asDouble()).isEqualTo(1.0);
        assertThat(result.citation().path("goldEvidencePrecision").asDouble()).isEqualTo(1.0);
        assertThat(result.citation().path("goldEvidenceRecall").asDouble()).isEqualTo(0.5);
        assertThat(result.graph().path("entityRecall").asDouble()).isEqualTo(1.0);
        assertThat(result.graph().path("relationRecall").asDouble()).isEqualTo(1.0);
        assertThat(result.failures().toString()).contains("CITATION_MISS");
    }

    @Test
    void marksCitationMetricsNotApplicableWhenAnswerGenerationIsDisabled() throws Exception {
        JsonNode evaluationCase = mapper.readTree("""
                {"evidenceGroups":[{"alternatives":[{"sourceBlockIds":["a"]}]}],"expectedGraph":{}}
                """);
        JsonNode response = mapper.readTree("""
                {"retrieval":{"reranked":[]},"graphTrace":{}}
                """);

        var result = engine.calculate(evaluationCase, response, false);

        assertThat(result.citation().path("applicable").asBoolean()).isFalse();
        assertThat(result.failures().toString()).doesNotContain("CITATION_MISS");
    }
}
