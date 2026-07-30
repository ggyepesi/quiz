package quiz.web;

import objectview.Viewable;
import objectview.group.ViewableGroup;

import java.util.Collection;

/**
 * A headless dataset: a named type plus the Viewables it loads. Deliberately
 * UI-free (unlike {@code objectview.viewconfig.DomainViews}) so the web backend can serve
 * data without constructing Swing views.
 */
public interface ViewableSource {

    /** Stable type name used in URLs, e.g. "OscarNomination". */
    String type();

    /** Loads (or fetches) the instances. May be slow; the store caches it. */
    Collection<? extends Viewable> load() throws Exception;

    /**
     * Optional group hierarchy (root). Lets quizzes be scoped to a subgroup
     * (e.g. NBA teams, European states). Null when the source has no groups.
     * Should reuse the same load as {@link #load()} (not reload).
     */
    default ViewableGroup<?> rootGroup() throws Exception {
        return null;
    }

    /**
     * The DECLARED groupable dimensions the client can re-facet by (live). Empty by
     * default; a generated source derives them from the model + data. Served so the
     * client offers "group by …" and the view executes the grouping on demand.
     */
    default java.util.List<quiz.web.sources.Dimension> dimensions() throws Exception {
        return java.util.List.of();
    }

    /**
     * Per-field COVERAGE over this source's members (present vs. missing per field) —
     * the first consistency check. Empty by default; a generated source computes it.
     */
    default java.util.List<quiz.web.sources.Coverage.FieldCoverage> coverage()
            throws Exception {
        return java.util.List.of();
    }
}
