package quiz.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A generated multiple-choice quiz, ready to play in the web client.
 *
 * <p>Each question's {@code prompt} reuses {@link QuizableView.Field}, so a
 * prompt can be an image (e.g. a logo), text, or any rendered field — the
 * frontend already knows how to draw it. {@code options} are values of the
 * answer field (the correct one plus distractors), and {@code answer} is the
 * correct option (client-side checking is fine for a casual quiz).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Quiz(
        String type,
        String prompt,
        String ask,
        List<Question> questions) {

    public record Question(
            List<QuizableView.Field> prompts,
            List<String> options,
            String answer,
            // When the answer field is image-valued, the image URLs for each
            // option (parallel to {@code options}): a list per option, so a
            // single image is one-element and a collection (e.g. a set of
            // border charts) is many — rendered as a carousel. Null when the
            // answer isn't an image.
            List<List<String>> optionImages) {

        public Question(
                List<QuizableView.Field> prompts,
                List<String> options,
                String answer) {
            this(prompts, options, answer, null);
        }
    }
}
