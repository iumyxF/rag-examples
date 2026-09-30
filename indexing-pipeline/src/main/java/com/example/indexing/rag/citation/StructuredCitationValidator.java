package com.example.indexing.rag.citation;

import com.example.indexing.rag.answer.AnswerDraft;
import com.example.indexing.rag.context.BuiltContext;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class StructuredCitationValidator implements CitationValidator {
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

    @Override
    public ValidationResult validate(AnswerDraft draft, BuiltContext context) {
        Set<Integer> available = new LinkedHashSet<>();
        context.citations().forEach(c -> available.add(c.id()));
        Set<Integer> referenced = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(draft.answer());
        while (matcher.find()) {
            referenced.add(Integer.parseInt(matcher.group(1)));
        }
        Set<Integer> invalid = new LinkedHashSet<>(referenced);
        invalid.addAll(draft.usedCitationIds());
        invalid.removeAll(available);
        boolean consistent = new LinkedHashSet<>(draft.usedCitationIds()).equals(referenced);
        return new ValidationResult(
                invalid.isEmpty() && consistent,
                draft,
                List.copyOf(invalid),
                invalid.isEmpty()
                        ? (consistent ? "valid" : "正文引用与 usedCitationIds 不一致")
                        : "引用了不存在的证据: " + invalid);
    }
}
