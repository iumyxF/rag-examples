package com.example.indexing.rag.context;

import com.example.indexing.config.RagProperties;
import com.example.indexing.retrieval.RetrievalCandidate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class TokenBudgetContextBuilder implements ContextBuilder {
    private final int maxTokens;

    public TokenBudgetContextBuilder(RagProperties p) {
        this.maxTokens = p.context().maxTokens();
    }

    @Override
    public BuiltContext build(List<RetrievalCandidate> candidates) {
        StringBuilder text = new StringBuilder();
        List<BuiltContext.Citation> citations = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>();
        Map<String, Integer> perDocument = new HashMap<>();
        int used = 0;
        for (RetrievalCandidate candidate : candidates) {
            var c = candidate.chunk();
            int count = perDocument.getOrDefault(c.documentId(), 0);
            if (count >= 4 || used + c.estimatedTokens() > maxTokens) {
                continue;
            }
            int id = citations.size() + 1;
            String locator =
                    c.pageNumber() != null
                            ? "page=" + c.pageNumber()
                            : c.sheetName() != null ? "sheet=" + c.sheetName() : "section=" + c.sectionPath();
            text.append("[")
                    .append(id)
                    .append("] file=")
                    .append(c.fileName())
                    .append(" ")
                    .append(locator)
                    .append("\n")
                    .append(c.content())
                    .append("\n\n");
            citations.add(
                    new BuiltContext.Citation(
                            id,
                            c.id(),
                            c.documentId(),
                            c.fileName(),
                            c.pageNumber(),
                            c.pageStart(),
                            c.pageEnd(),
                            c.sourceBlockIds(),
                            c.sheetName(),
                            c.sectionPath(),
                            excerpt(c.content())));
            chunkIds.add(c.id());
            used += c.estimatedTokens();
            perDocument.put(c.documentId(), count + 1);
        }
        return new BuiltContext(text.toString(), citations, chunkIds);
    }

    private String excerpt(String value) {
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }
}
