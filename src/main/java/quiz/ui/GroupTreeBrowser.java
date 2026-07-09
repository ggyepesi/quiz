package quiz.ui;

import quiz.Quizable;
import quiz.QuizableFieldPaths;
import quiz.QuizableGroup;
import quiz.ui.viewconfig.FieldTypeSource;
import quiz.ui.viewconfig.QuizablePanelConfig;
import quiz.ui.viewconfig.QuizablePanelConfigEditor;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A grouped-result browser: a search / sort / fields bar over a {@link GroupTreeView}
 * outline. Search filters members within the buckets, Sort orders members at the
 * leaves, and Fields chooses which fields the member cards show (so references
 * collapse to chips like the modelbuilder rather than expanding). It's DATA-centric
 * — it filters/sorts the group's members (via the shared {@link
 * QuizablePanelSearchAndSort}), not live cards — so it works over the collapsible
 * tree, where {@link QuizableSearchPanel} (which drives a flat cards panel) can't.
 */
public final class GroupTreeBrowser extends JPanel {

    private final GroupTreeView tree;
    private final QuizablePanelSearchAndSort engine = new QuizablePanelSearchAndSort();
    private final List<Quizable> allMembers;

    private final JTextField search = new JTextField(26);
    private final javax.swing.Timer debounce;

    // Shared config editors (dynamic-aware via the sample): all fields for search,
    // the user's chosen subset for the cards, and chosen keys for sort.
    private final QuizablePanelConfigEditor searchEditor;
    private final QuizablePanelConfigEditor viewEditor;
    private final QuizablePanelConfigEditor sortEditor;

    public GroupTreeBrowser(QuizableGroup root,
                            Class<? extends Quizable> memberClass, Quizable sample,
                            Set<String> hiddenFields, FieldTypeSource fieldTypes) {
        Set<String> hidden = hiddenFields == null ? Set.of() : hiddenFields;
        this.allMembers = new ArrayList<>(root == null ? List.of() : root.getMembers());

        searchEditor = editor(QuizablePanelConfig.of(memberClass), sample, hidden, fieldTypes);
        // Cards: all fields, references name-only (chips) — the modelbuilder look.
        viewEditor = editor(QuizablePanelConfig.of(memberClass), sample, hidden, fieldTypes);
        // Sort: nothing preselected — the user picks the key(s).
        QuizablePanelConfig sortBase = QuizablePanelConfig.of(memberClass);
        sortBase.setAllFields(false);
        sortEditor = editor(sortBase, sample, hidden, fieldTypes);

        this.tree = new GroupTreeView(root, viewEditor.getConfig());

        setLayout(new BorderLayout(6, 6));
        add(bar(), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(tree);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);

        debounce = new javax.swing.Timer(200, e -> applySearch());
        debounce.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });
    }

    private static QuizablePanelConfigEditor editor(QuizablePanelConfig cfg, Quizable sample,
                                                    Set<String> hidden, FieldTypeSource types) {
        QuizablePanelConfigEditor e = new QuizablePanelConfigEditor(cfg, true, sample);
        e.setHiddenFields(hidden);
        e.setFieldTypes(types);
        return e;
    }

    private JComponent bar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.add(new JLabel("Search:"));
        p.add(search);
        p.add(button("Sort…", () -> openEditor("Sort members by", sortEditor, this::applySort)));
        p.add(button("Fields…", () -> openEditor("Fields shown on cards", viewEditor,
                () -> tree.setCardConfig(viewEditor.getConfig()))));
        return p;
    }

    /** Filter members to those matching every search token (across all fields). */
    private void applySearch() {
        String text = search.getText().trim();
        if (text.isBlank()) {
            tree.setMemberFilter(null);
            return;
        }
        List<String> tokens = new ArrayList<>();
        for (String t : text.toLowerCase().split("\\s+")) {
            if (!t.isBlank()) {
                tokens.add(t);
            }
        }
        Map<String, List<Quizable>> hits =
                engine.searchQuizables(allMembers, tokens, searchEditor.getConfig());
        Set<Quizable> matched = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<Quizable> hs : hits.values()) {
            matched.addAll(hs);
        }
        tree.setMemberFilter(matched::contains);
    }

    /** Order members by the chosen sort key(s), reusing the shared sort logic. Members
     *  are shared instances across buckets, so an identity rank orders every leaf. */
    private void applySort() {
        List<QuizableFieldPaths.FieldPath> paths =
                QuizableFieldPaths.collect(sortEditor.getConfig());
        if (paths.isEmpty()) {
            tree.setMemberOrder(null);
            return;
        }
        List<Quizable> sorted = engine.sortQuizables(allMembers, paths);
        Map<Quizable, Integer> rank = new IdentityHashMap<>();
        for (int i = 0; i < sorted.size(); i++) {
            rank.put(sorted.get(i), i);
        }
        tree.setMemberOrder(Comparator.comparingInt(q -> rank.getOrDefault(q, Integer.MAX_VALUE)));
    }

    private void openEditor(String title, QuizablePanelConfigEditor editor, Runnable onApply) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(this), title,
                Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setLayout(new BorderLayout(8, 8));
        dialog.add(new JScrollPane(editor), BorderLayout.CENTER);

        JButton apply = new JButton("Apply");
        apply.addActionListener(e -> {
            dialog.dispose();
            onApply.run();
        });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(apply);
        dialog.add(south, BorderLayout.SOUTH);

        dialog.setSize(560, 620);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }
}
