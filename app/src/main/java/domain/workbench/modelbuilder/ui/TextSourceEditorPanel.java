package domain.workbench.modelbuilder.ui;

import domain.config.ConfigurationReferenceProjection;
import domain.config.GeneratedProjectModel;
import domain.config.TextSource;
import domain.datasource.evidence.TextPassages;
import domain.workbench.ui.ConfigurationTreePanel;
import objectview.utils.swing.GridBagUtils;
import wikipedia.WikipediaArticleClient;

import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Edits one text source: the work it is the text of, the site, its pages in reading
 * order and where its author divided it. Its name is renamed from the project tree, like
 * every declaration there.
 *
 * <p>Like the other configuration editors, controls hold a draft that {@link #applyEdits()}
 * writes when the reader leaves the text source or saves; an imported text source is
 * shown and never written.</p>
 */
final class TextSourceEditorPanel extends JPanel {

    private final JTextField workField = new JTextField(12);
    private final JComboBox<WikipediaArticleClient.Site> siteBox =
            new JComboBox<>(WikipediaArticleClient.Site.values());
    private final JComboBox<TextPassages.Division> divisionBox =
            new JComboBox<>(TextPassages.Division.values());
    private final JTextArea pagesArea = new JTextArea(6, 48);
    private final JLabel title = new JLabel(" ");
    private final ConfigurationTreePanel configuration =
            new ConfigurationTreePanel("text-source-configuration", "Text source settings");
    private final JPanel definition = new JPanel(new BorderLayout(4, 4));
    private TextSource shown;

    TextSourceEditorPanel() {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        workField.setName("textSource.work");
        siteBox.setName("textSource.site");
        divisionBox.setName("textSource.division");
        pagesArea.setName("textSource.pages");
        workField.setToolTipText("The Wikidata item of the work this is the text of, e.g. Q205882");
        pagesArea.setToolTipText("One page title per line, in reading order, exactly as the "
                + "site names it (subpages keep their /)");
        siteBox.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list, Object value, int index, boolean selected,
                    boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof WikipediaArticleClient.Site site) setText(site.label());
                return this;
            }
        });
        divisionBox.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(
                    javax.swing.JList<?> list, Object value, int index, boolean selected,
                    boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof TextPassages.Division division) setText(division.label());
                return this;
            }
        });

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(2, 0, 2, 6);
        int y = 0;
        GridBagUtils.labeledRow(form, c, y++, "Work (Wikidata item):", WikidataIdLines.qid(workField));
        GridBagUtils.labeledRow(form, c, y++, "Site:", siteBox);
        GridBagUtils.labeledRow(form, c, y++, "Pages, one per line:", new JScrollPane(pagesArea));
        GridBagUtils.labeledRow(form, c, y++, "Divided by:", divisionBox);
        add(title, BorderLayout.NORTH);
        definition.add(form, BorderLayout.CENTER);
        definition.add(new JLabel("<html>A story recipe reads the text sources it names; each work reads "
                + "those that name it.</html>"), BorderLayout.SOUTH);
        add(configuration, BorderLayout.CENTER);
    }

    /** Shows {@code text}; null shows nothing selected. */
    void edit(GeneratedProjectModel project, TextSource text) {
        shown = text;
        workField.setText(text == null ? "" : text.workQid());
        siteBox.setSelectedItem(text == null ? WikipediaArticleClient.Site.WIKISOURCE : text.site());
        pagesArea.setText(text == null ? "" : String.join("\n", text.pages()));
        divisionBox.setSelectedItem(text == null
                ? TextPassages.Division.NUMBERED_CHAPTERS : text.division());
        refreshDeclaration(project);
    }

    /** Updates the declaration name and references without touching the draft controls. */
    void refreshDeclaration(GeneratedProjectModel project) {
        title.setText(shown == null ? "Select a text source."
                : "<html><b>" + escape(shown.name()) + "</b></html>");
        configuration.setVisible(shown != null);
        configuration.show(shown == null ? null
                : ConfigurationReferenceProjection.forTextSource(project, shown), node -> definition);
    }

    ConfigurationTreePanel configurationTree() { return configuration; }

    /** Whether the draft differs from the shown text source. */
    boolean differs() {
        return shown != null && !shown.isImported()
                && !(work().equals(shown.workQid()) && Objects.equals(site(), shown.site())
                        && Objects.equals(division(), shown.division())
                        && pages().equals(shown.pages()));
    }

    /** Writes the draft to the shown text source; true when that changed it. */
    boolean applyEdits() {
        if (!differs()) return false;
        shown.workQid(work());
        shown.site(site());
        shown.division(division());
        shown.pages(pages());
        return true;
    }

    private String work() {
        return workField.getText().trim().toUpperCase(java.util.Locale.ROOT);
    }

    private WikipediaArticleClient.Site site() {
        return (WikipediaArticleClient.Site) siteBox.getSelectedItem();
    }

    private TextPassages.Division division() {
        return (TextPassages.Division) divisionBox.getSelectedItem();
    }

    private List<String> pages() {
        return Arrays.stream(pagesArea.getText().split("\\R"))
                .map(String::trim).filter(line -> !line.isEmpty()).distinct().toList();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
