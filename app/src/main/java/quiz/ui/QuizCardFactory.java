package quiz.ui;

import objectview.render.Card;
import objectview.render.RenderContext;
import objectview.field.FieldPath;
import objectview.viewconfig.ViewConfig;
import objectview.Viewable;

import java.util.ArrayList;
import java.util.Collection;

/**
 * The single desktop construction path for quiz Cards. It applies presentation
 * policy to a defensive ViewConfig copy and supplies a consistent render
 * context, while leaving selection styling and click handling to callers.
 */
public final class QuizCardFactory {
    private final Collection<? extends Viewable> quizContext;

    public QuizCardFactory(Collection<? extends Viewable> quizContext) {
        this.quizContext = quizContext == null ? java.util.List.of() : quizContext;
    }

    public Card create(
            Viewable viewable,
            ViewConfig sourceConfig,
            QuizCardRole role) {
        return create(viewable, sourceConfig,
                QuizCardPresentation.forRole(role), null);
    }

    public Card create(
            Viewable viewable,
            ViewConfig sourceConfig,
            QuizCardRole role,
            Collection<? extends Viewable> localContext) {
        return create(viewable, sourceConfig,
                QuizCardPresentation.forRole(role), localContext);
    }

    public Card create(
            Viewable viewable,
            ViewConfig sourceConfig,
            QuizCardPresentation presentation,
            Collection<? extends Viewable> localContext) {
        if (viewable == null) {
            throw new IllegalArgumentException("Quiz card needs a Viewable");
        }
        if (presentation == null) {
            throw new IllegalArgumentException("Quiz card presentation is required");
        }

        ViewConfig config = configurationFor(
                viewable, sourceConfig, presentation);
        Collection<? extends Viewable> contextItems = localContext != null
                ? localContext
                : presentation.useQuizContext() ? quizContext : java.util.List.of();
        RenderContext context = new RenderContext(contextItems);
        context.putClassConfig(viewable.getClass(), config);

        return new Card(
                Card.identitySetOf(),
                Card.identitySetOf(),
                context,
                true,
                viewable,
                config,
                presentation.fill(),
                FieldPath.ROOT);
    }

    /**
     * Exposed for deterministic tests and for non-Card renderers that need the
     * exact same desktop presentation policy.
     */
    public ViewConfig configurationFor(
            Viewable viewable,
            ViewConfig sourceConfig,
            QuizCardPresentation presentation) {
        ViewConfig config = sourceConfig == null
                ? ViewConfig.of(viewable == null ? null : viewable.getClass())
                : sourceConfig.copy();
        if (viewable != null && config.getCls() == null) {
            config.setCls(viewable.getClass());
        }
        if (presentation.disableConfigListeners()) {
            config.setAddListener(false);
        }
        switch (presentation.mediaSize()) {
            case FULL -> setThumbRecursively(config, false);
            // Existing LIST/ABCD answer cards set thumbnail mode at the root;
            // explicit nested choices remain authoritative.
            case THUMBNAIL -> config.setThumb(true);
            case PRESERVE -> {
                // Keep the field-level choices made in ViewConfigEditor.
            }
        }
        return config;
    }

    private static void setThumbRecursively(ViewConfig config, boolean thumb) {
        if (config == null) {
            return;
        }
        config.setThumb(thumb);
        for (ViewConfig child : config.getFields().values()) {
            setThumbRecursively(child, thumb);
        }
    }
}
