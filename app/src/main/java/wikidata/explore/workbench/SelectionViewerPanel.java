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

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** A deliberately small editor: one named selection and one list of its entities. */
public class SelectionViewerPanel extends JPanel {
    private static final int POPULATION_SAMPLE_LIMIT = 200;

    private final GeneratedProjectModel project;
    private final WikidataApiClient api;
    private final WikidataSparqlClient sparql;
    private final JComboBox<Selection> selectionBox = new JComboBox<>();
    private final JButton newButton = new JButton("New");
    private final JButton renameButton = new JButton("Rename");
    private final JButton deleteButton = new JButton("Delete");
    private final DefaultListModel<EntityRow> entitiesModel = new DefaultListModel<>();
    private final JList<EntityRow> entities = new JList<>(entitiesModel);
    private final JPanel reusableHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
    private final JButton removeButton = new JButton("Remove selected");
    // Labels this panel has been told, by QID. A vocabulary stores QIDs only, so
    // without this the six prizes just picked BY LABEL redraw as six bare QIDs.
    private final Map<String, String> knownLabels = new HashMap<>();
    private final JLabel status = new JLabel(" ");

    public SelectionViewerPanel(GeneratedProjectModel project, WikidataApiClient api,
                                WikidataSparqlClient sparql) {
        super(new BorderLayout(6, 6));
        this.project = project;
        this.api = api;
        this.sparql = sparql;

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("Vocabulary / population:"));
        top.add(selectionBox);
        top.add(newButton);
        top.add(renameButton);
        top.add(deleteButton);
        selectionBox.setRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(
                    value == null ? "" : value.name() + "  [" + value.kind() + "]");
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            label.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });

        entities.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        entities.setCellRenderer((list, value, index, selected, focus) -> {
            JLabel label = new JLabel(value.label() + " (" + value.qid() + ")");
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(5, 7, 5, 7));
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
        add(new JScrollPane(entities), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        selectionBox.addActionListener(e -> showChosen());
        entities.addListSelectionListener(e -> updateActions());
        newButton.addActionListener(e -> createVocabulary());
        renameButton.addActionListener(e -> renameChosen());
        deleteButton.addActionListener(e -> deleteChosen());
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

    /** Points the selector at one selection, falling back to the first that exists. */
    private void choose(Selection selection) {
        if (selection != null && project.selections().contains(selection)) {
            selectionBox.setSelectedItem(selection);
            return;
        }
        if (selectionBox.getItemCount() > 0) selectionBox.setSelectedIndex(0);
    }

    private void showChosen() {
        entitiesModel.clear();
        Selection selection = chosenSelection();
        if (selection == null) {
            status.setText("No vocabulary or population declared in this domain.");
            updateActions();
            return;
        }
        if (selection instanceof VocabularySelection vocabulary) {
            // Show what is known WITHOUT waiting: a vocabulary is explicit QIDs, and the
            // panel must stay usable offline. Labels arrive after, if a client exists.
            vocabulary.valueQids().forEach(qid -> entitiesModel.addElement(row(qid)));
            status.setText(vocabulary.name() + ": " + vocabulary.valueQids().size() + " entities");
            updateActions();
            if (api != null && !vocabulary.valueQids().isEmpty()) resolveLabels(vocabulary);
            return;
        }
        status.setText("Loading a sample of " + selection.name() + "…");
        updateActions();
        new SwingWorker<List<WikidataDynamicObject>, Void>() {
            @Override protected List<WikidataDynamicObject> doInBackground() {
                return new SelectionContentResolver().resolve(
                        selection, sparql, api, POPULATION_SAMPLE_LIMIT, null);
            }
            @Override protected void done() {
                if (selection != chosenSelection()) return;
                List<WikidataDynamicObject> content;
                try { content = get(); } catch (Exception ex) { content = List.of(); }
                entitiesModel.clear();
                content.forEach(value -> entitiesModel.addElement(
                        new EntityRow(value.qid(), value.getDisplayName())));
                status.setText(selection.name() + ": " + content.size() + " sampled entities");
                updateActions();
            }
        }.execute();
    }

    private EntityRow row(String qid) {
        return new EntityRow(qid, knownLabels.get(qid));
    }

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
                entitiesModel.clear();
                vocabulary.valueQids().forEach(qid -> entitiesModel.addElement(row(qid)));
            }
        }.execute();
    }

    private void learn(String qid, String label) {
        if (qid != null && label != null && !label.isBlank() && !label.equals(qid)) {
            knownLabels.put(qid, label);
        }
    }

    private void createVocabulary() {
        String name = prompt("New vocabulary name", "New vocabulary");
        if (name == null) return;
        if (project.findSelection(name) != null) {
            status.setText("A vocabulary or population named " + name + " already exists.");
            return;
        }
        VocabularySelection created = new VocabularySelection(name);
        project.addSelection(created);
        refreshSelections();
        choose(created);
        status.setText("Created vocabulary " + name + ". Add entities from reusable selections.");
    }

    private void renameChosen() {
        Selection selected = chosenSelection();
        if (!(selected instanceof VocabularySelection)) return;
        String next = prompt("Rename vocabulary", selected.name());
        if (next == null) return;
        if (!project.renameSelection(selected.name(), next)) {
            status.setText("Could not rename: the name is blank or already used.");
            return;
        }
        refreshSelections();
        choose(selected);
        status.setText("Renamed vocabulary to " + next + ".");
    }

    private void deleteChosen() {
        Selection selected = chosenSelection();
        if (!(selected instanceof VocabularySelection)) return;
        int answer = JOptionPane.showConfirmDialog(this,
                "Delete vocabulary " + selected.name() + "?", "Delete vocabulary",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        if (!project.removeSelection(selected.name())) {
            status.setText("Cannot delete " + selected.name() + ": the model still references it.");
            return;
        }
        refreshSelections();
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
    }

    private void removeSelectedEntities() {
        if (!(chosenSelection() instanceof VocabularySelection vocabulary)) return;
        LinkedHashSet<String> removed = new LinkedHashSet<>(
                entities.getSelectedValuesList().stream().map(EntityRow::qid).toList());
        vocabulary.valueQids(vocabulary.valueQids().stream()
                .filter(qid -> !removed.contains(qid)).toList());
        showChosen();
        status.setText("Removed " + removed.size() + " entities — "
                + vocabulary.valueQids().size() + " remain.");
    }

    private void updateActions() {
        boolean vocabulary = chosenSelection() instanceof VocabularySelection;
        renameButton.setEnabled(vocabulary);
        deleteButton.setEnabled(vocabulary);
        removeButton.setEnabled(vocabulary && !entities.isSelectionEmpty());
    }

    private String prompt(String message, String initial) {
        String value = JOptionPane.showInputDialog(this, message, initial);
        if (value == null) return null;
        value = value.trim();
        if (value.isBlank()) {
            status.setText("A vocabulary name is required.");
            return null;
        }
        return value;
    }

    private record EntityRow(String qid, String label) {
        EntityRow {
            label = label == null || label.isBlank() ? qid : label;
        }
    }
}
