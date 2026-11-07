package workbench;

import wikidata.explore.model.CategoryCandidatePolicy;
import wikidata.explore.model.WikipediaCategoryMapping;
import wikidata.explore.model.WikipediaCategoryRule;
import wikidata.ui.WikidataIdText;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** One editor for the Wikipedia-category field source used by ModelBuilder and Transform. */
public final class WikipediaCategoryRuleEditor extends JPanel {
    private final JTextField pattern = new JTextField(30);
    private final JComboBox<CategoryCandidatePolicy> policy =
            new JComboBox<>(CategoryCandidatePolicy.values());
    private final JPanel mappingsPanel = new JPanel();
    private final List<WikipediaCategoryMapping> mappings = new ArrayList<>();
    private final String observedCategory;

    public WikipediaCategoryRuleEditor(
            WikipediaCategoryRule current, String observedCategory) {
        super(new BorderLayout(6, 6));
        WikipediaCategoryRule safe = current == null
                ? new WikipediaCategoryRule() : current;
        this.observedCategory = cleanCategory(observedCategory);
        pattern.setText(safe.pattern());
        policy.setSelectedItem(safe.policy());
        safe.mappings().stream().filter(java.util.Objects::nonNull)
                .map(WikipediaCategoryMapping::copy).forEach(mappings::add);
        buildUi();
        refreshMappings();
    }

    public static WikipediaCategoryRule edit(Component parent,
            WikipediaCategoryRule current, String observedCategory) {
        WikipediaCategoryRuleEditor editor =
                new WikipediaCategoryRuleEditor(current, observedCategory);
        while (JOptionPane.showConfirmDialog(parent, editor,
                "Wikipedia category relation", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            String problem = editor.problem();
            if (problem == null) return editor.rule();
            JOptionPane.showMessageDialog(parent, problem,
                    "Wikipedia category relation", JOptionPane.WARNING_MESSAGE);
        }
        return null;
    }

    public WikipediaCategoryRule rule() {
        WikipediaCategoryRule rule = new WikipediaCategoryRule();
        rule.pattern(pattern.getText());
        rule.policy((CategoryCandidatePolicy) policy.getSelectedItem());
        mappings.stream().map(WikipediaCategoryMapping::copy)
                .forEach(rule.mappings()::add);
        return rule;
    }

    public static String summary(WikipediaCategoryRule rule) {
        if (rule == null || !rule.configured()) return "none";
        List<String> sources = new ArrayList<>();
        if (rule.patternConfigured()) sources.add(rule.pattern());
        long exact = rule.mappings().stream().filter(java.util.Objects::nonNull)
                .filter(WikipediaCategoryMapping::configured).count();
        if (exact > 0) sources.add(exact + " exact mapping" + (exact == 1 ? "" : "s"));
        return String.join(" + ", sources) + " — " + rule.policy();
    }

    private void buildUi() {
        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 3, 2, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0;
        c.gridx = 0; c.gridy = 0;
        fields.add(new JLabel("Category title pattern:"), c);
        c.gridx = 1; c.weightx = 1;
        fields.add(pattern, c);
        c.gridx = 0; c.gridy++; c.gridwidth = 2; c.weightx = 1;
        fields.add(new JLabel("Optional; use exactly one <value> placeholder."), c);

        if (!observedCategory.isBlank()) {
            JPanel observed = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            observed.add(new JLabel("Observed: " + observedCategory));
            JButton usePattern = new JButton("Use as pattern");
            usePattern.addActionListener(event -> pattern.setText(observedCategory));
            JButton map = new JButton("Add exact mapping…");
            map.addActionListener(event -> addMapping(observedCategory));
            observed.add(usePattern);
            observed.add(map);
            c.gridy++;
            fields.add(observed, c);
        }

        c.gridy++; c.gridwidth = 1; c.weightx = 0;
        fields.add(new JLabel("Result policy:"), c);
        c.gridx = 1; c.weightx = 1;
        fields.add(policy, c);
        add(fields, BorderLayout.NORTH);

        mappingsPanel.setLayout(new BoxLayout(mappingsPanel, BoxLayout.Y_AXIS));
        JScrollPane mappingsScroll = new JScrollPane(mappingsPanel);
        mappingsScroll.setBorder(BorderFactory.createTitledBorder(
                "Exact category → Wikidata value mappings"));
        mappingsScroll.setPreferredSize(new Dimension(620, 170));
        add(mappingsScroll, BorderLayout.CENTER);

        JButton add = new JButton("Add exact mapping…");
        add.addActionListener(event -> addMapping(""));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        actions.add(add);
        add(actions, BorderLayout.SOUTH);
    }

    private void addMapping(String suggestedCategory) {
        JTextField category = new JTextField(cleanCategory(suggestedCategory), 30);
        JTextField qid = new JTextField(10);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; c.gridy = 0;
        form.add(new JLabel("Wikipedia category:"), c);
        c.gridx = 1; c.weightx = 1; form.add(category, c);
        c.gridx = 0; c.gridy++; c.weightx = 0;
        form.add(new JLabel("Field value QID:"), c);
        JPanel qidLine = new JPanel(new BorderLayout(4, 0));
        qidLine.add(qid, BorderLayout.WEST);
        qidLine.add(WikidataIdText.following(qid), BorderLayout.CENTER);
        c.gridx = 1; c.weightx = 1; form.add(qidLine, c);

        while (JOptionPane.showConfirmDialog(this, form, "Exact category mapping",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                == JOptionPane.OK_OPTION) {
            WikipediaCategoryMapping mapping =
                    new WikipediaCategoryMapping(category.getText(), qid.getText());
            if (!mapping.configured()) {
                JOptionPane.showMessageDialog(this,
                        "Enter a Wikipedia category title and a valid QID.");
                continue;
            }
            if (mappings.stream().anyMatch(existing -> existing.category()
                    .equalsIgnoreCase(mapping.category()))) {
                JOptionPane.showMessageDialog(this,
                        "That Wikipedia category already has a mapping.");
                continue;
            }
            mappings.add(mapping);
            refreshMappings();
            return;
        }
    }

    private void refreshMappings() {
        mappingsPanel.removeAll();
        if (mappings.isEmpty()) {
            JLabel empty = new JLabel("No exact mappings configured.");
            empty.setForeground(new Color(95, 95, 95));
            empty.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            mappingsPanel.add(empty);
        } else {
            for (WikipediaCategoryMapping mapping : List.copyOf(mappings)) {
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
                row.setAlignmentX(Component.LEFT_ALIGNMENT);
                row.add(new JLabel(mapping.category() + " →"));
                WikidataIdText value = new WikidataIdText();
                value.setPlainText(mapping.valueQid());
                row.add(value);
                JButton remove = new JButton("Remove");
                remove.addActionListener(event -> {
                    mappings.remove(mapping);
                    refreshMappings();
                });
                row.add(remove);
                mappingsPanel.add(row);
            }
        }
        mappingsPanel.revalidate();
        mappingsPanel.repaint();
    }

    private String problem() {
        String enteredPattern = pattern.getText() == null ? "" : pattern.getText().trim();
        if (!enteredPattern.isBlank()
                && enteredPattern.split("<value>", -1).length - 1 != 1) {
            return "The optional pattern must contain exactly one <value> placeholder.";
        }
        if (mappings.stream().anyMatch(mapping -> mapping == null || !mapping.configured())) {
            return "Each exact mapping needs a Wikipedia category and a valid value QID.";
        }
        long distinctCategories = mappings.stream()
                .map(WikipediaCategoryMapping::category)
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .distinct().count();
        if (distinctCategories != mappings.size()) {
            return "A Wikipedia category may be mapped only once.";
        }
        if (enteredPattern.isBlank() && mappings.isEmpty()) {
            return "Configure a <value> pattern or at least one exact category mapping.";
        }
        return null;
    }

    private static String cleanCategory(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.regionMatches(true, 0, "Category:", 0, 9)
                ? clean.substring(9).trim() : clean;
    }
}
