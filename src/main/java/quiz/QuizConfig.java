package quiz;

import objectview.Viewable;

import objectview.viewconfig.ViewablePanelConfig;

/**
 * Bundled configurations for viewing, searching, and sorting quiz data.
 */
public final class QuizConfig {

    private final ViewablePanelConfig viewConfig;
    private final ViewablePanelConfig searchConfig;
    private final ViewablePanelConfig sortConfig;

    public QuizConfig(Class<? extends Viewable> cls) {
        this.viewConfig = ViewablePanelConfig.of(cls).initializeAllFields(true);
        this.searchConfig = ViewablePanelConfig.of(cls).initializeAllFields(true);
        this.sortConfig = ViewablePanelConfig.of(cls).initializeAllFields(true);
    }

    public ViewablePanelConfig getViewConfig() { return viewConfig; }
    public ViewablePanelConfig getSearchConfig() { return searchConfig; }
    public ViewablePanelConfig getSortConfig() { return sortConfig; }

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
