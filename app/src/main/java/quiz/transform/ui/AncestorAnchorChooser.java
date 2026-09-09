package quiz.transform.ui;

import objectview.Viewable;
import workbench.SimpleDocumentListener;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Explicit searchable picker for the semantic anchors of an ancestor group. */
final class AncestorAnchorChooser extends JPanel {

    private static final int MAX_MATCHES = 200;

    private final List<TransformController.AncestorCandidate> candidates;
    private final JTextField search = new JTextField(28);
    private final DefaultListModel<TransformController.AncestorCandidate> foundModel =
            new DefaultListModel<>();
    private final JList<TransformController.AncestorCandidate> found =
            new JList<>(foundModel);
    private final JLabel matchStatus = new JLabel();
    private final DefaultListModel<Viewable> selectedModel = new DefaultListModel<>();
    private final JList<Viewable> selected = new JList<>(selectedModel);
    private final Map<String, Viewable> chosen = new LinkedHashMap<>();

    AncestorAnchorChooser(List<TransformController.AncestorCandidate> candidates) {
        super(new BorderLayout(6, 6));
        this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JPanel find = new JPanel(new BorderLayout(4, 4));
        find.add(new JLabel("Find an ancestor by name or identifier:"), BorderLayout.NORTH);
        find.add(search, BorderLayout.CENTER);
        found.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        found.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = new JLabel(candidateChoiceLabel(value));
            label.setOpaque(true);
            label.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        JScrollPane foundScroll = new JScrollPane(found);
        foundScroll.setPreferredSize(new Dimension(500, 180));
        JPanel matches = new JPanel(new BorderLayout(4, 2));
        matches.add(foundScroll, BorderLayout.CENTER);
        matches.add(matchStatus, BorderLayout.SOUTH);
        find.add(matches, BorderLayout.SOUTH);
        add(find, BorderLayout.NORTH);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton add = new JButton("Add selected");
        add.addActionListener(e -> addSelected());
        JButton remove = new JButton("Remove selected");
        remove.addActionListener(e -> removeSelected());
        actions.add(add);
        actions.add(remove);
        add(actions, BorderLayout.CENTER);

        JPanel anchors = new JPanel(new BorderLayout(4, 4));
        anchors.add(new JLabel("Selected ancestor groups:"), BorderLayout.NORTH);
        selected.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        selected.setCellRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = new JLabel(candidateLabel(value));
            label.setOpaque(true);
            label.setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
            label.setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
            return label;
        });
        JScrollPane selectedScroll = new JScrollPane(selected);
        selectedScroll.setPreferredSize(new Dimension(500, 110));
        anchors.add(selectedScroll, BorderLayout.CENTER);
        add(anchors, BorderLayout.SOUTH);

        search.getDocument().addDocumentListener(
                SimpleDocumentListener.of(this::refreshMatches));
        found.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (event.getClickCount() == 2) addSelected();
            }
        });
        refreshMatches();
    }

    List<Viewable> selectedAnchors() {
        return List.copyOf(chosen.values());
    }

    void search(String text) {
        search.setText(text == null ? "" : text);
    }

    void selectFound(int... indices) {
        found.setSelectedIndices(indices);
    }

    void addSelected() {
        for (TransformController.AncestorCandidate candidate
                : found.getSelectedValuesList()) {
            Viewable value = candidate.value();
            chosen.putIfAbsent(identity(value), value);
        }
        refreshSelected();
    }

    private void removeSelected() {
        for (Viewable value : selected.getSelectedValuesList()) chosen.remove(identity(value));
        refreshSelected();
    }

    private void refreshSelected() {
        selectedModel.clear();
        chosen.values().forEach(selectedModel::addElement);
    }

    private void refreshMatches() {
        foundModel.clear();
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        int matching = 0;
        for (TransformController.AncestorCandidate candidate : candidates) {
            if (query.isBlank() || candidateLabel(candidate.value())
                    .toLowerCase(Locale.ROOT).contains(query)) {
                matching++;
                if (foundModel.size() < MAX_MATCHES) {
                    foundModel.addElement(candidate);
                }
            }
        }
        if (matching == 0) {
            matchStatus.setText("No matching ancestors.");
        } else if (matching > foundModel.size()) {
            matchStatus.setText("Showing " + foundModel.size() + " of " + matching
                    + " matching ancestors.");
        } else {
            matchStatus.setText("Showing all " + matching + " matching ancestors.");
        }
    }

    int shownMatchCount() { return foundModel.size(); }
    String matchStatusText() { return matchStatus.getText(); }

    /**
     * Both sizes, because an anchor is chosen on what it would classify.
     *
     * <p>"below it" is what picking this ancestor would catch; "direct" says whether
     * that is one flat bucket or a layer with structure under it. The two differ by
     * two orders of magnitude on a real hierarchy, and showing only the direct count
     * ranked the flattest bucket first.
     */
    static String candidateChoiceLabel(TransformController.AncestorCandidate candidate) {
        int children = candidate == null ? 0 : candidate.directChildren();
        int below = candidate == null ? 0 : candidate.descendants();
        return candidateLabel(candidate == null ? null : candidate.value())
                + String.format("  ·  %,d below it, %,d direct", below, children);
    }

    static String candidateLabel(Viewable value) {
        if (value == null) return "";
        String name = value.getReferenceLabel();
        String id = value.getIdentifier();
        if (name == null || name.isBlank()) return id == null ? "" : id;
        return id == null || id.isBlank() || name.equals(id) ? name : name + " — " + id;
    }

    private static String identity(Viewable value) {
        return (value.identityTypeName() == null ? "" : value.identityTypeName())
                + "\u001f" + value.getIdentifier();
    }
}
