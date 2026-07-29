package quiz.ui;

/**
 * Desktop rendering policy for a quiz card.
 */
public record QuizCardPresentation(
        QuizCardRole role,
        MediaSize mediaSize,
        boolean fill,
        boolean disableConfigListeners,
        boolean useQuizContext) {

    public enum MediaSize {
        PRESERVE,
        FULL,
        THUMBNAIL
    }

    public static QuizCardPresentation forRole(QuizCardRole role) {
        return switch (role) {
            case PROMPT -> new QuizCardPresentation(
                    role, MediaSize.FULL, true, true, true);
            case OPTION -> new QuizCardPresentation(
                    role, MediaSize.THUMBNAIL, false, false, true);
            case PAIR_PROMPT -> new QuizCardPresentation(
                    role, MediaSize.PRESERVE, true, false, false);
            case PAIR_ANSWER -> new QuizCardPresentation(
                    role, MediaSize.PRESERVE, false, false, false);
            case TIMELINE_ENTRY -> new QuizCardPresentation(
                    role, MediaSize.THUMBNAIL, false, false, true);
        };
    }
}
