package quiz.ordering;

import objectview.viewconfig.ViewConfig;

/**
 * Definition of an ordering game. ViewConfig controls visible prompt/answer
 * content; OrderKey is evaluated separately and remains hidden until reveal.
 */
public record OrderingQuizConfig(
        ViewConfig promptView,
        ViewConfig answerView,
        OrderKey orderKey,
        SortDirection direction,
        EqualValuePolicy equalValuePolicy) {

    public OrderingQuizConfig {
        if (promptView == null || answerView == null || orderKey == null) {
            throw new IllegalArgumentException("Prompt, answer, and order key are required");
        }
        direction = direction == null ? SortDirection.ASCENDING : direction;
        equalValuePolicy = equalValuePolicy == null
                ? EqualValuePolicy.EQUIVALENT : equalValuePolicy;
    }
}
