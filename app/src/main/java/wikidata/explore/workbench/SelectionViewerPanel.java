package wikidata.explore.workbench;

import wikidata.WikidataSparqlClient;
import wikidata.api.WikidataApiClient;
import wikidata.explore.extract.SelectionContentResolver;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.Selection;
import wikidata.explore.model.VocabularySelection;
import workbench.SelectionsButton;
import workbench.WorkbenchSelections;
import objectview.Viewable;
import objectview.view.SearchableView;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A deliberately small editor: one named selection and one list of its entities. */
public class SelectionViewerPanel extends JPanel {
    private final GeneratedProjectModel project;
    private final WikidataApiClient api;
    private final WikidataSparqlClient sparql;
    private final JComboBox<Selection> selectionBox = new JComboBox<>();
    private final JPanel entities = new JPanel(new BorderLayout());
    private SearchableView entitiesView;
    private List<? extends Viewable> shownEntities = List.of();
    private List<String> selectedQids = List.of();
    private final JPanel reusableHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JButton removeButton = new JButton("Remove selected");
    // Labels this panel has been told, by QID. A vocabulary stores QIDs only, so
    // without this the six prizes just picked BY LABEL redraw as six bare QIDs.
    private final Map<String, String> knownLabels = new HashMap<>();
    private final JLabel status = new JLabel(" ");
    private Consumer<Selection> afterChange = ignored -> {};

    public SelectionViewerPanel(GeneratedProjectModel project, WikidataApiClient api,
                                WikidataSparqlClient sparql) {
        super(new BorderLayout(6, 6));
        this.project = project;
        this.api = api;
        this.sparql = sparql;

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("Vocabulary / population:"));
        top.add(selectionBox);
        selectionBox.setRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(
                    value == null ? "" : value.name() + "  [" + value.kind() + "]");
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });

        JPanel entityActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        entityActions.add(reusableHolder);
        entityActions.add(removeButton);
        JPanel south = new JPanel(new BorderLayout());
        south.add(entityActions, BorderLayout.NORTH);
        south.add(status, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(entities, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        selectionBox.addActionListener(e -> showChosen());
        removeButton.addActionListener(e -> removeSelectedEntities());
        refreshSelections();
    }

    public void selections(WorkbenchSelections value) {
        reusableHolder.removeAll();
        if (value != null) {
            SelectionsButton button = new SelectionsButton(value).useEntities(
                    "Add selected entities",
                    SelectionsButton.Cardinality.MULTIPLE,
                    () -> chosenSelection() instanceof VocabularySelection,
                    this::addEntities);
            button.setName("vocabularyReusableSelections");
            reusableHolder.add(button);
        }
        reusableHolder.revalidate();
        reusableHolder.repaint();
        updateActions();
    }

    public void afterChange(Consumer<Selection> consumer) {
        afterChange = consumer == null ? ignored -> {} : consumer;
    }

    private Selection chosenSelection() {
        return (Selection) selectionBox.getSelectedItem();
    }

    public void refreshSelections() {
        // Carry the chosen SELECTION across the rebuild, not its name or its position.
        // A rename mutates the object, so the name it had no longer finds it; a
        // position is only correct while nothing reorders the model.
        Selection previous = chosenSelection();
        selectionBox.removeAllItems();
        for (Selection selection : project.selections()) {
            selectionBox.addItem(selection);
        }
        choose(previous);
        showChosen();
    }

    /** Shows the Selection chosen in the model tree, rather than making the reader
     * find the same object again in this panel's combo box. */
    public void edit(Selection selection) {
        refreshSelections();
        choose(selection);
        showChosen();
    }

    /** Points the selector at one selection, falling back to the first that exists. */
    private void choose(Selection selection) {
        if (selection != null && project.selections().contains(selection)) {
            selectionBox.setSelectedItem(selection);
            return;
        }
        if (selectionBox.getItemCount() > 0) selectionBox.setSelectedIndex(0);
    }

    private void showChosen() {
        showEntities(List.of());
        Selection selection = chosenSelection();
        if (selection == null) {
            status.setText("No vocabulary or population declared in this domain.");
            updateActions();
            return;
        }
        if (selection instanceof VocabularySelection vocabulary) {
            // Show what is known WITHOUT waiting: a vocabulary is explicit QIDs, and the
            // panel must stay usable offline. Labels arrive after, if a client exists.
            showEntities(vocabulary.valueQids().stream().map(this::entity).toList());
            status.setText(vocabulary.name() + ": " + vocabulary.valueQids().size() + " entities");
            updateActions();
            if (api != null && !vocabulary.valueQids().isEmpty()) resolveLabels(vocabulary);
            return;
        }
        if (selection instanceof wikidata.explore.model.PopulationSelection population) {
            showEntities(population.instanceQids().stream().map(this::entity).toList());
            status.setText(selection.name() + ": " + population.instanceQids().size()
                    + " instances");
        }
        updateActions();
    }

    private WikidataDynamicObject entity(String qid) {
        WikidataDynamicObject value = new WikidataDynamicObject(
                qid, knownLabels.getOrDefault(qid, qid));
        value.type("Selection member");
        return value;
    }

    private void showEntities(List<? extends Viewable> values) {
        shownEntities = List.copyOf(values);
        selectedQids = List.of();
        entities.removeAll();
        if (values.isEmpty()) {
            entities.add(new JLabel("  (none)"), BorderLayout.CENTER);
            entitiesView = null;
        } else {
            entitiesView = SearchableView.builder(values)
                    .sample(values.getFirst()).columns(2)
                    .valueLinker(wikidata.ui.WikidataLinks.valueLinker())
                    .selectionSetListener(selected -> {
                        selectedQids = selected.stream().filter(Viewable.class::isInstance)
                                .map(Viewable.class::cast)
                                .map(quiz.source.SourceIdentities::wikidataQid)
                                .filter(java.util.Objects::nonNull).distinct().toList();
                        updateActions();
                    }).build();
            entities.add(entitiesView, BorderLayout.CENTER);
        }
        entities.revalidate();
        entities.repaint();
    }

    SearchableView entitiesViewForTest() { return entitiesView; }
    List<? extends Viewable> shownEntitiesForTest() { return shownEntities; }

    /** Learns the labels of a vocabulary's QIDs, then redraws it with them. */
    private void resolveLabels(VocabularySelection vocabulary) {
        new SwingWorker<List<WikidataDynamicObject>, Void>() {
            @Override protected List<WikidataDynamicObject> doInBackground() {
                return new SelectionContentResolver().resolve(vocabulary, api, null);
            }
            @Override protected void done() {
                List<WikidataDynamicObject> content;
                try { content = get(); } catch (Exception ex) { return; }
                content.forEach(value -> learn(value.qid(), value.getDisplayName()));
                if (vocabulary != chosenSelection()) return;
                showEntities(vocabulary.valueQids().stream()
                        .map(SelectionViewerPanel.this::entity).toList());
            }
        }.execute();
    }

    private void learn(String qid, String label) {
        if (qid != null && label != null && !label.isBlank() && !label.equals(qid)) {
            knownLabels.put(qid, label);
        }
    }

    private void addEntities(List<WorkbenchSelections.Entity> selected) {
        selected.forEach(entity -> learn(entity.qid(), entity.label()));
        // Reusable entities are validated when they enter WorkbenchSelections, so this
        // source has no rejects. Keep that fact explicit at the shared growth boundary:
        // another add source may accept raw tokens and must report what it rejected.
        grow(selected.stream().map(WorkbenchSelections.Entity::qid).toList(), 0);
    }

    /** The one way a vocabulary grows: added in order, never twice, rejects reported. */
    private void grow(List<String> qids, long rejected) {
        if (!(chosenSelection() instanceof VocabularySelection vocabulary)) return;
        LinkedHashSet<String> values = new LinkedHashSet<>(vocabulary.valueQids());
        int before = values.size();
        values.addAll(qids);
        vocabulary.valueQids(List.copyOf(values));
        showChosen();
        status.setText("Added " + (values.size() - before) + " — " + values.size()
                + " total" + (rejected > 0 ? ", " + rejected + " rejected" : "") + ".");
        afterChange.accept(vocabulary);
    }

    private void removeSelectedEntities() {
        if (!(chosenSelection() instanceof VocabularySelection vocabulary)) return;
        LinkedHashSet<String> removed = new LinkedHashSet<>(selectedQids);
        vocabulary.valueQids(vocabulary.valueQids().stream()
                .filter(qid -> !removed.contains(qid)).toList());
        showChosen();
        status.setText("Removed " + removed.size() + " entities — "
                + vocabulary.valueQids().size() + " remain.");
        afterChange.accept(vocabulary);
    }

    private void updateActions() {
        boolean vocabulary = chosenSelection() instanceof VocabularySelection;
        removeButton.setEnabled(vocabulary && !selectedQids.isEmpty());
    }
}
