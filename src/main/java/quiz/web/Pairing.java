package quiz.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A matching game: pair each prompt with its answer. The client lays the
 * prompts out against their (shuffled) answers and the player links them. The
 * desktop counterpart is {@code quiz.QuizPairs}.
 *
 * <p>Correctness is checked by answer string, so two prompts that share an
 * answer (e.g. two teams in one league) both accept that answer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Pairing(String type, String prompt, String ask, List<Pair> pairs) {

    public record Pair(List<QuizableView.Field> prompts, String answer) {}
}
