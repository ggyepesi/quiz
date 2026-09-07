package wikidata.explore.workbench;

import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a class's instances are named: an ORDERED list of fields, joined.
 *
 * <p>The same control the key uses, asking the same shape of question — which fields,
 * in which order — because it is the same shape of answer. It was a mode, a field box
 * and a template box: three controls for "one field", "several fields" and "none", where
 * the ordered list says all three by being empty, having one entry, or having several.
 *
 * <p>Every shipped display name is exactly that: {@code nominee}, {@code source},
 * {@code {category} — {year}}, {@code {laureates} — {category}}. What the free-text
 * template could also express — literal words, another separator — nothing uses, and a
 * template that is not a plain join is shown as the given value it is rather than
 * silently rewritten. That rewriting is what the aggregate editor's field checkboxes did:
 * they composed a template and read it back by substring, so "Best {category}" came back
 * as "{category}".
 *
 * <p>An empty list means the instances keep the name they already have, which is a real
 * answer for a kind whose instances have one — see {@link
 * CanonicalEditorPolicy#labelSource}.
 */
final class DisplayNameEditor extends JPanel {

    private static final String JOIN = " — ";
    private static final Pattern FIELD_TOKEN = Pattern.compile("\\{([^{}]+)\\}");

    // The shared control: chosen things in order, plus a chooser of what could be
    // added. Ordered, because a name reads in the order its parts are joined.
    private final OrderedChoiceList<String> fields = new OrderedChoiceList<>(true);
    private final JLabel hint = new JLabel(" ");
    private final JButton replaceTemplate =
            new JButton("Replace configured template with this list");
    private final JPanel status = new JPanel(new BorderLayout(4, 4));
    /** A stored template this list cannot express, shown rather than overwritten. */
    private String unexpressibleTemplate = "";

    private GeneratedClassModel clazz;
    private Runnable onChange = () -> { };

    DisplayNameEditor() {
        super(new BorderLayout(4, 4));
        setBorder(BorderFactory.createTitledBorder("Display name"));
        fields.title("Named by, in order — empty means the name it already has");
        fields.setToolTipText("The fields whose values make the display name, joined in "
                + "the order shown. Empty: the instance keeps the name it has.");
        fields.onChange(() -> {
            applyEdits();
            refreshHint();
            onChange.run();
        });
        replaceTemplate.addActionListener(event -> {
            // This is deliberately an action rather than an automatic translation.
            // A template with literal text cannot be represented by the list, so the
            // modeller explicitly chooses when the list replaces that stored value.
            unexpressibleTemplate = "";
            applyEdits();
            refreshHint();
            onChange.run();
        });
        hint.setForeground(new Color(0xB00020));
        add(fields, BorderLayout.CENTER);
        status.add(hint, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    /** Run after the reader changes the name, so an owner can refresh what it shows. */
    void onChange(Runnable listener) {
        onChange = listener == null ? () -> { } : listener;
    }

    void show(GeneratedClassModel value) {
        clazz = value;
        unexpressibleTemplate = "";
        List<String> chosen = new ArrayList<>();
        if (value != null) {
            CanonicalSpec spec = value.canonical();
            switch (spec.displayNameMode()) {
                case LABEL -> { }
                case FIELD -> {
                    if (!spec.displayNameField().isBlank()) {
                        chosen.add(spec.displayNameField());
                    }
                }
                case TEMPLATE -> {
                    List<String> parsed = fieldsOf(spec.displayNameTemplate());
                    if (parsed.isEmpty() && !spec.displayNameTemplate().isBlank()) {
                        unexpressibleTemplate = spec.displayNameTemplate();
                    } else {
                        chosen.addAll(parsed);
                    }
                }
            }
        }
        fields.show(chosen, candidates());
        fields.mode(value == null
                ? OrderedChoiceList.Mode.FIXED : OrderedChoiceList.Mode.EDITABLE);
        refreshHint();
    }

    /**
     * The list, in the model's terms.
     *
     * <p>Not a fourth spelling: the mode is what a list of that length MEANS, and the
     * spec keeps all three because that is what the runtime reads.
     */
    void applyEdits() {
        if (clazz == null || !unexpressibleTemplate.isBlank()) return;
        List<String> chosen = fields.chosen();
        CanonicalSpec.DisplayNameMode mode = chosen.isEmpty()
                ? CanonicalSpec.DisplayNameMode.LABEL
                : chosen.size() == 1
                        ? CanonicalSpec.DisplayNameMode.FIELD
                        : CanonicalSpec.DisplayNameMode.TEMPLATE;
        clazz.canonical(CanonicalEditorPolicy.spec(clazz.classKind(), mode,
                chosen.isEmpty() ? "" : chosen.get(0), template(chosen),
                clazz.canonical()));
    }

    CanonicalSpec.DisplayNameMode mode() {
        List<String> chosen = fields.chosen();
        return chosen.isEmpty() ? CanonicalSpec.DisplayNameMode.LABEL
                : chosen.size() == 1 ? CanonicalSpec.DisplayNameMode.FIELD
                        : CanonicalSpec.DisplayNameMode.TEMPLATE;
    }

    /** What is wrong with the current choice, or blank when nothing is. */
    String warning() {
        if (clazz == null) return "";
        if (!unexpressibleTemplate.isBlank()) {
            return "Named by a template this list cannot express: \""
                    + unexpressibleTemplate + "\". Choose the replacement fields above, "
                    + "then explicitly replace the configured template.";
        }
        if (!fields.chosen().isEmpty()) return "";
        return CanonicalEditorPolicy.labelSource(clazz.classKind()).isBlank()
                ? "These instances have no name of their own — name them by a field."
                : "";
    }

    private void refreshHint() {
        String warning = warning();
        hint.setText(warning.isBlank() ? " " : warning);
        status.remove(replaceTemplate);
        if (!unexpressibleTemplate.isBlank()) {
            status.add(replaceTemplate, BorderLayout.EAST);
        }
        status.revalidate();
        status.repaint();
    }

    /** Every field the runtime can render as part of a name. */
    private List<String> candidates() {
        List<String> names = new ArrayList<>();
        if (clazz == null) return names;
        for (GeneratedFieldModel field : clazz.fields()) {
            if (field == null || field.isNameField()) continue;
            names.add(field.name());
        }
        return names;
    }

    /** {@code {a} — {b}}, which is what every shipped multi-field name already is. */
    private static String template(List<String> chosen) {
        if (chosen.size() < 2) return "";
        List<String> tokens = new ArrayList<>();
        for (String field : chosen) tokens.add("{" + field + "}");
        return String.join(JOIN, tokens);
    }

    /** The fields a template names, when it is a plain join of them and nothing else. */
    private static List<String> fieldsOf(String template) {
        List<String> named = new ArrayList<>();
        if (template == null || template.isBlank()) return named;
        Matcher tokens = FIELD_TOKEN.matcher(template);
        StringBuilder rebuilt = new StringBuilder();
        while (tokens.find()) {
            if (rebuilt.length() > 0) rebuilt.append(JOIN);
            rebuilt.append(tokens.group());
            named.add(tokens.group(1));
        }
        // Anything but a plain join — a literal word, another separator — is a template
        // this list would rewrite, and rewriting a modeller's answer is what it must not
        // do. Say it cannot express it instead.
        return rebuilt.toString().equals(template.trim()) ? named : List.of();
    }
}
