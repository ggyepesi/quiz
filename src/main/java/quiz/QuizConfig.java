package quiz;

import objectview.Viewable;

import objectview.viewconfig.QuizablePanelConfig;

/**
 * Bundled configurations for viewing, searching, and sorting quiz data.
 */
public final class QuizConfig {

    private final QuizablePanelConfig viewConfig;
    private final QuizablePanelConfig searchConfig;
    private final QuizablePanelConfig sortConfig;

    public QuizConfig(Class<? extends Viewable> cls) {
        this.viewConfig = QuizablePanelConfig.of(cls).initializeAllFields(true);
        this.searchConfig = QuizablePanelConfig.of(cls).initializeAllFields(true);
        this.sortConfig = QuizablePanelConfig.of(cls).initializeAllFields(true);
    }

    public QuizablePanelConfig getViewConfig() { return viewConfig; }
    public QuizablePanelConfig getSearchConfig() { return searchConfig; }
    public QuizablePanelConfig getSortConfig() { return sortConfig; }

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
