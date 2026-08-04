package wikidata.explore.workbench;

import objectview.render.Card;
import objectview.render.RenderContext;
import objectview.utils.swing.GridBagUtils;
import objectview.viewconfig.ViewConfig;
import objectview.Viewable;
import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.SelectionContentResolver;
import wikidata.explore.extract.ValueVocabularyDiscovery;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.Selection;
import wikidata.explore.model.VocabularySelection;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Browse a {@link Selection}'s CONTENT (slice 2 of the Selection construct). A Selection is
 * never a served product, but its content is inspectable: pick a Selection, resolve
 * its members (labelled), and render them as the same cards used everywhere else —
 * so making the Oscar categories a vocabulary hides nothing, it just stops them
 * being a class. Reuses the shared Card rendering rather than a bespoke widget.
 */
public class SelectionViewerPanel extends JPanel {

    /** Sample size for a POPULATION selection's bounded subject query. */
    private static final int POPULATION_SAMPLE_LIMIT = 200;

    private final GeneratedProjectModel project;
    private final WikidataApiClient api;
    private final WikidataSparqlClient sparql;

    private final JComboBox<String> selectionBox = new JComboBox<>();
    private final JButton showButton = new JButton("Show content");
    private final JLabel status = new JLabel(" ");
    private final JPanel cards = new JPanel(new GridBagLayout());
    private final JScrollPane cardsScroll = new JScrollPane(cards);

    private final JTextField newNameField = new JTextField(14);
    private final JTextField newQidsField = new JTextField(24);
    private final JButton addButton = new JButton("Add & show");

    // Discover a vocabulary from a property's DISTINCT values over sample subjects —
    // e.g. the P31 types of some nominees, the P136 genres of some works.
    private final JTextField discNameField = new JTextField(14);
    private final JTextField discPidField = new JTextField(6);
    private final JTextField discSubjectsField = new JTextField(20);
    private final JButton discoverButton = new JButton("Discover & add");

    public SelectionViewerPanel(GeneratedProjectModel project, WikidataApiClient api,
                                WikidataSparqlClient sparql) {
        super(new BorderLayout(6, 6));
        this.project = project;
        this.api = api;
        this.sparql = sparql;
        cardsScroll.getVerticalScrollBar().setUnitIncrement(18);
        cardsScroll.setPreferredSize(new Dimension(460, 520));

        JPanel viewRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        viewRow.add(new JLabel("Selection:"));
        viewRow.add(selectionBox);
        viewRow.add(showButton);

        // Inline "declare a vocabulary": a name + QIDs, so the construct is usable
        // without a separate editor — paste the Oscar categories, see them at once.
        JPanel newRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        newRow.add(new JLabel("New vocabulary:"));
        newRow.add(newNameField);
        newRow.add(new JLabel("QIDs:"));
        newRow.add(newQidsField);
        newRow.add(addButton);
        newQidsField.setToolTipText("Comma- or space-separated QIDs, e.g. Q102427 Q106301");

        // Discover a vocabulary: name + property + a few sample subjects, and the
        // property's distinct values over them become the vocabulary. This is how a
        // referenced class's `type` (P31) / `genre` (P136) gets a value domain
        // without pasting QIDs — sample a handful of nominees / works.
        JPanel discRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        discRow.add(new JLabel("Discover vocab:"));
        discRow.add(discNameField);
        discRow.add(new JLabel("prop:"));
        discRow.add(discPidField);
        discRow.add(new JLabel("subjects:"));
        discRow.add(discSubjectsField);
        discRow.add(discoverButton);
        discPidField.setToolTipText("The property whose distinct values form the "
                + "vocabulary, e.g. P31 (type) or P136 (genre).");
        discSubjectsField.setToolTipText("A few sample subject QIDs to profile "
                + "(e.g. some nominee or work QIDs), comma/space-separated.");

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(viewRow);
        top.add(newRow);
        top.add(discRow);

        add(top, BorderLayout.NORTH);
        add(cardsScroll, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        showButton.addActionListener(e -> showSelected());
        addButton.addActionListener(e -> addVocabulary());
        discoverButton.addActionListener(e -> discoverVocabulary());
    }

    /** Run a value-vocabulary discovery and materialize it as a VocabularySelection. */
    private void discoverVocabulary() {
        String name = discNameField.getText().trim();
        String pid = discPidField.getText().trim();
        if (name.isBlank()) {
            status.setText("Give the vocabulary a name.");
            return;
        }
        if (!pid.matches("(?i)P\\d+")) {
            status.setText("Enter a property, e.g. P31 (type) or P136 (genre).");
            return;
        }
        List<String> subjects = new ArrayList<>();
        for (String tok : discSubjectsField.getText().split("[,\\s]+")) {
            if (tok != null && tok.trim().matches("(?i)Q\\d+")) {
                subjects.add(tok.trim());
            }
        }
        if (subjects.isEmpty()) {
            status.setText("Enter a few sample subject QIDs (e.g. some nominees).");
            return;
        }
        if (sparql == null) {
            status.setText("No SPARQL client available for discovery.");
            return;
        }

        status.setText("Discovering " + pid + " values over " + subjects.size()
                + " subject(s)…");
        discoverButton.setEnabled(false);
        new SwingWorker<List<WikidataDynamicObject>, Void>() {
            @Override
            protected List<WikidataDynamicObject> doInBackground() {
                return new ValueVocabularyDiscovery()
                        .discover(subjects, pid, 200, 500, sparql, api, null);
            }

            @Override
            protected void done() {
                List<WikidataDynamicObject> values;
                try {
                    values = get();
                } catch (Exception ex) {
                    values = new ArrayList<>();
                }
                discoverButton.setEnabled(true);
                if (values.isEmpty()) {
                    status.setText("No " + pid + " values discovered over those subjects.");
                    return;
                }
                List<String> qids = new ArrayList<>();
                for (WikidataDynamicObject v : values) {
                    qids.add(v.qid());
                }
                VocabularySelection s = new VocabularySelection(name);
                s.valueQids(qids);
                project.addSelection(s);
                discNameField.setText("");
                discPidField.setText("");
                discSubjectsField.setText("");
                refreshSelections();
                selectionBox.setSelectedItem(name + "  [" + Selection.Kind.VOCABULARY + "]");
                render(s, values);
                status.setText(name + " [VOCABULARY]: discovered " + values.size()
                        + " value(s) — set a field's target to \"" + name
                        + "\" to use it as its value domain.");
            }
        }.execute();
    }

    private void addVocabulary() {
        String name = newNameField.getText().trim();
        if (name.isBlank()) {
            status.setText("Give the vocabulary a name.");
            return;
        }
        java.util.List<String> qids = new java.util.ArrayList<>();
        for (String tok : newQidsField.getText().split("[,\\s]+")) {
            if (tok != null && tok.trim().matches("(?i)Q\\d+")) {
                qids.add(tok.trim());
            }
        }
        if (qids.isEmpty()) {
            status.setText("Enter at least one QID (e.g. Q102427).");
            return;
        }
        VocabularySelection s = new VocabularySelection(name);
        s.valueQids(qids);
        project.addSelection(s);
        newNameField.setText("");
        newQidsField.setText("");
        refreshSelections();
        selectionBox.setSelectedItem(name + "  [" + Selection.Kind.VOCABULARY + "]");
        showSelected();
    }

    /** Re-read the project's selections into the selector — call when the window opens,
     *  since selections can be added/edited while it's closed. */
    public void refreshSelections() {
        String previous = (String) selectionBox.getSelectedItem();
        selectionBox.removeAllItems();
        for (Selection s : project.selections()) {
            selectionBox.addItem(s.name() + "  [" + s.kind() + "]");
        }
        if (previous != null) {
            selectionBox.setSelectedItem(previous);
        }
        boolean any = selectionBox.getItemCount() > 0;
        showButton.setEnabled(any);
        if (!any) {
            cards.removeAll();
            cards.revalidate();
            cards.repaint();
            status.setText("No selections declared in this domain.");
        }
    }

    private void showSelected() {
        int i = selectionBox.getSelectedIndex();
        if (i < 0 || i >= project.selections().size()) {
            return;
        }
        Selection selection = project.selections().get(i);
        status.setText("Resolving \"" + selection.name() + "\" …");
        showButton.setEnabled(false);
        new SwingWorker<List<WikidataDynamicObject>, Void>() {
            @Override
            protected List<WikidataDynamicObject> doInBackground() {
                return new SelectionContentResolver().resolve(
                        selection, sparql, api, POPULATION_SAMPLE_LIMIT, null);
            }

            @Override
            protected void done() {
                List<WikidataDynamicObject> content;
                try {
                    content = get();
                } catch (Exception ex) {
                    content = new ArrayList<>();
                }
                render(selection, content);
                showButton.setEnabled(true);
            }
        }.execute();
    }

    private void render(Selection selection, List<WikidataDynamicObject> content) {
        cards.removeAll();

        List<Viewable> viewables = new ArrayList<>(content);
        RenderContext context = new RenderContext(viewables);

        ViewConfig config = ViewConfig.of(WikidataDynamicObject.class);
        // Render all (non-hidden) fields so each member shows its Wikidata link field
        // besides its title. A bare vocabulary member has only that, so this stays clean.
        config.setAllFields(true);
        config.setThumb(false);
        config.setAddListener(false);
        context.putClassConfig(WikidataDynamicObject.class, config);

        int row = 0;
        for (WikidataDynamicObject o : content) {
            Card card = new Card(o, config.copy(), context, false);
            context.registerTopLevel(o, card);
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createEmptyBorder(2, 2, 2, 2),
                    BorderFactory.createLineBorder(Color.LIGHT_GRAY)));
            GridBagUtils.stackedCard(cards, row++, card);
        }
        GridBagUtils.verticalGlue(cards, row);

        cards.revalidate();
        cards.repaint();
        boolean sampled = selection.kind() == Selection.Kind.POPULATION;
        status.setText(selection.name() + " [" + selection.kind() + "]: "
                + content.size() + (sampled ? " sampled member(s)" : " member(s)"));
    }
}
