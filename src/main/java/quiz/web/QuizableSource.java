package quiz.web;

import quiz.Quizable;

import java.util.Collection;

/**
 * A headless dataset: a named type plus the Quizables it loads. Deliberately
 * UI-free (unlike {@code quiz.ui.QuizableViews}) so the web backend can serve
 * data without constructing Swing views.
 */
public interface QuizableSource {

    /** Stable type name used in URLs, e.g. "OscarNomination". */
    String type();

    /** Loads (or fetches) the instances. May be slow; the store caches it. */
    Collection<? extends Quizable> load() throws Exception;
}
