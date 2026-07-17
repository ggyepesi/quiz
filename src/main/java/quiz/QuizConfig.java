package quiz;

import objectview.Viewable;

import objectview.viewconfig.ViewConfig;

/**
 * Bundled configurations for viewing, searching, and sorting quiz data.
 */
public final class QuizConfig {

    private final ViewConfig viewConfig;
    private final ViewConfig searchConfig;
    private final ViewConfig sortConfig;

    public QuizConfig(Class<? extends Viewable> cls) {
        this.viewConfig = ViewConfig.of(cls).initializeAllFields(true);
        this.searchConfig = ViewConfig.of(cls).initializeAllFields(true);
        this.sortConfig = ViewConfig.of(cls).initializeAllFields(true);
    }

    public ViewConfig getViewConfig() { return viewConfig; }
    public ViewConfig getSearchConfig() { return searchConfig; }
    public ViewConfig getSortConfig() { return sortConfig; }

    /** Deep copy */
    public QuizConfig copy() {
        QuizConfig q = new QuizConfig(
                viewConfig.getCls() != null ? viewConfig.getCls()
                        : searchConfig.getCls());
        q.getViewConfig().setAllFields(viewConfig.isAllFields());
        q.getSearchConfig().setAllFields(searchConfig.isAllFields());
        q.getSortConfig().setAllFields(sortConfig.isAllFields());
        return q;
    }

    @Override public String toString() {
        return "QuizConfig(view=" + viewConfig + ", search=" + searchConfig + ", sort=" + sortConfig + ")";
    }
}
