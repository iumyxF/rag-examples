package com.example.indexing.rag.citation;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.indexing.rag.answer.AnswerDraft;
import com.example.indexing.rag.context.BuiltContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredCitationValidatorTest {
  private final StructuredCitationValidator validator = new StructuredCitationValidator();
  private final BuiltContext context =
      new BuiltContext(
          "",
          List.of(new BuiltContext.Citation(1, "c", "d", "f", 1, null, null, "e")),
          List.of("c"));

  @Test
  void acceptsConsistentCitation() {
    assertThat(validator.validate(new AnswerDraft("结论。[1]", List.of(1)), context).valid()).isTrue();
  }

  @Test
  void rejectsUnknownCitation() {
    assertThat(validator.validate(new AnswerDraft("结论。[2]", List.of(2)), context).invalidIds())
        .containsExactly(2);
  }

  @Test
  void rejectsMismatchBetweenBodyAndStructuredIds() {
    assertThat(validator.validate(new AnswerDraft("结论。[1]", List.of()), context).valid()).isFalse();
  }
}
