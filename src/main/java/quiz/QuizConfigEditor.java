package quiz;

import objectview.viewconfig.ViewablePanelConfigEditor;

import javax.swing.*;
import java.awt.*;

/**
 * Combined editor for view/search/sort configurations.
 */
public class QuizConfigEditor extends JPanel {

    private final QuizConfig quizConfig;
    private final ViewablePanelConfigEditor viewEditor;
    private final ViewablePanelConfigEditor searchEditor;
    private final ViewablePanelConfigEditor sortEditor;

    public QuizConfigEditor(QuizConfig config) {
        this.quizConfig = config;
        this.viewEditor = new ViewablePanelConfigEditor(config.getViewConfig());
        this.searchEditor = new ViewablePanelConfigEditor(config.getSearchConfig());
        this.sortEditor = new ViewablePanelConfigEditor(config.getSortConfig());
        buildLayout();
    }

    private void buildLayout() {
        setLayout(new BorderLayout());
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("View", viewEditor);
        tabs.addTab("Search", searchEditor);
        tabs.addTab("Sort", sortEditor);
        add(tabs, BorderLayout.CENTER);
    }

    public QuizConfig getQuizConfig() {
        return quizConfig;
    }
}
