package quiz.ui;

/**
 * Semantic use of a Card in a desktop quiz. The role selects rendering policy;
 * interaction state (idle, selected, correct, exhausted) remains separate.
 */
public enum QuizCardRole {
    PROMPT,
    OPTION,
    PAIR_PROMPT,
    PAIR_ANSWER,
    TIMELINE_ENTRY
}
