package quiz.web;

import quiz.Quizable;
import quiz.QuizableGroup;

import java.util.Collection;

/**
 * A headless dataset: a named type plus the Quizables it loads. Deliberately
 * UI-free (unlike {@code objectview.DomainViews}) so the web backend can serve
 * data without constructing Swing views.
 */
public interface QuizableSource {

    /** Stable type name used in URLs, e.g. "OscarNomination". */
    String type();

    /** Loads (or fetches) the instances. May be slow; the store caches it. */
    Collection<? extends Quizable> load() throws Exception;

    /**
     * Optional group hierarchy (root). Lets quizzes be scoped to a subgroup
     * (e.g. NBA teams, European states). Null when the source has no groups.
     * Should reuse the same load as {@link #load()} (not reload).
     */
    default QuizableGroup rootGroup() throws Exception {
        return null;
    }
}
