package com.example.indexing.rag.pipeline;

import com.example.indexing.config.RagProperties;
import com.example.indexing.document.DocumentIndexingService;
import com.example.indexing.graph.GraphRepository;
import com.example.indexing.graph.GraphSnapshot;
import com.example.indexing.rag.answer.AnswerDraft;
import com.example.indexing.rag.answer.AnswerGenerator;
import com.example.indexing.rag.citation.CitationValidator;
import com.example.indexing.rag.context.ContextBuilder;
import com.example.indexing.retrieval.CandidateRetriever;
import com.example.indexing.retrieval.FusionStrategy;
import com.example.indexing.retrieval.QueryUnderstandingService;
import com.example.indexing.retrieval.Reranker;
import com.example.indexing.retrieval.RetrievalCandidate;
import com.example.indexing.visualization.GraphViewBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

@Service
public class RagPipelineService {
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");
    private final QueryUnderstandingService understanding;
    private final List<CandidateRetriever> retrievers;
    private final DocumentIndexingService documents;
    private final FusionStrategy fusion;
    private final Reranker reranker;
    private final ContextBuilder contextBuilder;
    private final AnswerGenerator answers;
    private final CitationValidator validator;
    private final GraphRepository graph;
    private final GraphViewBuilder graphView;
    private final RagProperties properties;

    public RagPipelineService(
            QueryUnderstandingService understanding,
            List<CandidateRetriever> retrievers,
            DocumentIndexingService documents,
            FusionStrategy fusion,
            Reranker reranker,
            ContextBuilder contextBuilder,
            AnswerGenerator answers,
            CitationValidator validator,
            GraphRepository graph,
            GraphViewBuilder graphView,
            RagProperties properties) {
        this.understanding = understanding;
        this.retrievers = retrievers;
        this.documents = documents;
        this.fusion = fusion;
        this.reranker = reranker;
        this.contextBuilder = contextBuilder;
        this.answers = answers;
        this.validator = validator;
        this.graph = graph;
        this.graphView = graphView;
        this.properties = properties;
    }

    public RagResult query(String question, List<String> documentIds) {
        EvaluationExecution execution = evaluate(question, documentIds, true);
        var snapshot =
                graph.evidenceGraph(
                        execution.contextChunkIds(),
                        properties.graph().maxNodes(),
                        properties.graph().maxEdges());
        RagTrace trace =
                new RagTrace(
                        execution.queryPlan(),
                        execution.retrieval().entrySet().stream()
                                .filter(entry -> !entry.getKey().equals("fused") && !entry.getKey().equals("reranked"))
                                .collect(java.util.stream.Collectors.toMap(
                                        Map.Entry::getKey,
                                        Map.Entry::getValue,
                                        (left, right) -> left,
                                        LinkedHashMap::new)),
                        execution.retrieval().getOrDefault("fused", List.of()),
                        execution.retrieval().getOrDefault("reranked", List.of()),
                        execution.finalContext(),
                        execution.citationValidation());
        return new RagResult(
                execution.answer(),
                execution.contextCitations(),
                graphView.toMermaid(snapshot),
                trace);
    }

    public EvaluationExecution evaluate(
            String question, List<String> documentIds, boolean generateAnswer) {
        long totalStarted = System.nanoTime();
        Map<String, Long> timings = new LinkedHashMap<>();
        long stageStarted = System.nanoTime();
        var plan = understanding.understand(question, documentIds);
        timings.put("understandingMs", elapsedMs(stageStarted));
        Map<String, String> versions = documents.activeVersions(plan.documentIds());
        if (versions.isEmpty()) {
            throw new IllegalArgumentException("没有可检索的活动文档");
        }
        Map<String, List<RetrievalCandidate>> retrieval = new LinkedHashMap<>();
        for (CandidateRetriever retriever : retrievers) {
            stageStarted = System.nanoTime();
            retrieval.put(retriever.name(), retriever.retrieve(plan, versions));
            timings.put(retriever.name() + "Ms", elapsedMs(stageStarted));
        }
        stageStarted = System.nanoTime();
        List<RetrievalCandidate> fused = fusion.fuse(retrieval);
        timings.put("fusionMs", elapsedMs(stageStarted));
        retrieval.put("fused", fused);
        stageStarted = System.nanoTime();
        List<RetrievalCandidate> reranked = reranker.rerank(plan.rewrittenQuery(), fused);
        timings.put("rerankMs", elapsedMs(stageStarted));
        retrieval.put("reranked", reranked);

        List<String> graphChunkIds =
                retrieval.getOrDefault("graph", List.of()).stream()
                        .map(candidate -> candidate.chunk().id())
                        .toList();
        GraphSnapshot graphTrace =
                graph.evidenceGraph(
                        graphChunkIds, properties.graph().maxNodes(), properties.graph().maxEdges());

        if (!generateAnswer) {
            timings.put("totalMs", elapsedMs(totalStarted));
            return new EvaluationExecution(
                    plan,
                    null,
                    List.of(),
                    List.of(),
                    Map.copyOf(retrieval),
                    graphTrace,
                    versions,
                    documents.contentHashes(plan.documentIds()),
                    timings,
                    null,
                    "",
                    List.of());
        }

        stageStarted = System.nanoTime();
        var context = contextBuilder.build(reranked);
        if (context.citations().isEmpty()) {
            throw new IllegalArgumentException("未检索到可用于回答的证据");
        }
        AnswerDraft draft = answers.generate(question, context, null);
        var validation = validator.validate(draft, context);
        if (!validation.valid()) {
            draft = answers.generate(question, context, validation.message());
            validation = validator.validate(draft, context);
        }
        String validationMessage = validation.message();
        if (!validation.valid()) {
            draft = sanitize(draft, context.citations().size());
            validationMessage += "; 已移除非法引用";
        }
        timings.put("answerMs", elapsedMs(stageStarted));
        timings.put("totalMs", elapsedMs(totalStarted));
        return new EvaluationExecution(
                plan,
                draft.answer(),
                context.citations(),
                usedCitationIds(draft.answer(), context.citations().size()),
                Map.copyOf(retrieval),
                graphTrace,
                versions,
                documents.contentHashes(plan.documentIds()),
                timings,
                validationMessage,
                context.text(),
                context.chunkIds());
    }

    private long elapsedMs(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private List<Integer> usedCitationIds(String answer, int max) {
        Matcher matcher = CITATION.matcher(answer == null ? "" : answer);
        Set<Integer> used = new LinkedHashSet<>();
        while (matcher.find()) {
            int id = Integer.parseInt(matcher.group(1));
            if (id >= 1 && id <= max) {
                used.add(id);
            }
        }
        return List.copyOf(used);
    }

    private AnswerDraft sanitize(AnswerDraft draft, int max) {
        Matcher m = CITATION.matcher(draft.answer());
        StringBuffer clean = new StringBuffer();
        Set<Integer> used = new LinkedHashSet<>();
        while (m.find()) {
            int id = Integer.parseInt(m.group(1));
            if (id >= 1 && id <= max) {
                used.add(id);
                m.appendReplacement(clean, Matcher.quoteReplacement(m.group()));
            } else {
                m.appendReplacement(clean, "");
            }
        }
        m.appendTail(clean);
        return new AnswerDraft(clean.toString(), new ArrayList<>(used));
    }
}
