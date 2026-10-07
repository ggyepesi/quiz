package quiz;

import aux.Constants;
import flag.SportTeams;
import flag.States;
import language.Languages;
import mythology.MythologyEntities;
import nobel.NobelPrizes;
import objectview.Viewable;
import objectview.field.FieldPath;
import objectview.group.ViewableGroup;
import objectview.viewconfig.DomainViews;
import objectview.viewconfig.ViewConfig;
import oscar.OscarNominations;
import presidents.USPresidents;
import objectview.media.ImageBlurrer;
import objectview.render.GroupView;
import objectview.viewconfig.ViewConfigEditor;
import domain.DomainModel;
import quiz.transform.app.DomainCatalog;
import quiz.transform.ui.DomainEntry;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;

public class QuizFactory {

    // Register the quiz answer-image blur policy with objectview (which has no quiz/OCR
    // dependency of its own — it just calls the active blurrer).
    static {
        ImageBlurrer.setActive(new ImageBlurrer() {
            @Override public boolean blurs(String type, String name) {
                return quiz.ocr.QuizImageBlurrer.blurs(type, name);
            }
            @Override public java.awt.image.BufferedImage blur(
                    String type, String name, java.awt.image.BufferedImage src) {
                return quiz.ocr.QuizImageBlurrer.blur(type, name, src);
            }
        });
        // (SVG rasterization is registered via ServiceLoader — see
        // META-INF/services/objectview.utils.swing.SvgRasterizer -> aux.BatikSvgRasterizer,
        // so it works from any entry point, not only those that load QuizFactory.)
    }

    /** A built-in Viewable domain (icon, name, builder) — exposed so the transform
     * domain navigator can load the live implementation for conversion. QuizFactory
     * itself consumes only configured snapshots from {@link DomainCatalog#configured()}. */
    public record BuiltInDomain(String icon, String name, DomainViews views) {}

    private static final List<BuiltInDomain> BUILT_IN_DOMAINS = List.of(
            new BuiltInDomain("🐉", "Mythology", new MythologyEntities()),
            new BuiltInDomain("🏳️", "States", new States()),
            new BuiltInDomain("⚽", "Sport Teams", new SportTeams()),
            new BuiltInDomain("🏅", "Nobel Prizes", new NobelPrizes()),
            new BuiltInDomain("🇺🇸", "US Presidents", new USPresidents()),
            new BuiltInDomain("🗣️", "Languages", new Languages()),
            new BuiltInDomain("🎬", "Oscars", new OscarNominations())
    );

    public static List<BuiltInDomain> builtInDomains() {
        return BUILT_IN_DOMAINS;
    }

    private static final String PREF_LAST_DOMAIN = "lastDomain";
    private static final Preferences PREFS =
            Preferences.userNodeForPackage(QuizFactory.class);

    private static JFrame quizFrame;

    private final String domainName;
    private final String type;
    private final DomainModel domain;
    private final boolean configuredGrouping;
    private final Map<String, ? extends Viewable> viewables;
    private final GroupView rootView;

    record ServedClass(String name, int instances) {
        @Override public String toString() {
            return name + "  (" + instances + " instance"
                    + (instances == 1 ? "" : "s") + ")";
        }
    }

    record QuizSource(
            String type,
            List<Viewable> instances,
            Map<String, Viewable> viewables,
            ViewableGroup<?> root,
            boolean configuredGrouping) {}

    public static void main(String[] args) {
        Constants.setFontSizeMultiplier(1.5f);
        SwingUtilities.invokeLater(QuizFactory::showQuizSelector);
    }

    private static void showQuizSelector() {
        List<DomainEntry> configured = DomainCatalog.configured();
        JFrame frame = new JFrame("Select Quiz Source");
        frame.setLayout(new BoxLayout(frame.getContentPane(), BoxLayout.Y_AXIS));

        ButtonGroup group = new ButtonGroup();

        JPanel optionsPanel = new JPanel();
        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
        optionsPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        String lastDomain = PREFS.get(PREF_LAST_DOMAIN, "");

        java.awt.Font optionFont = new java.awt.Font(
                java.awt.Font.SANS_SERIF,
                java.awt.Font.PLAIN,
                22
        );

        for (int i = 0; i < configured.size(); i++) {
            DomainEntry option = configured.get(i);
            String label = option.name();
            JRadioButton radioButton = new JRadioButton(label);
            radioButton.setFont(optionFont);
            radioButton.setActionCommand(String.valueOf(i));
            radioButton.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            group.add(radioButton);
            optionsPanel.add(radioButton);

            if (option.name().equals(lastDomain)
                    || (lastDomain.isBlank() && i == 0)) {
                radioButton.setSelected(true);
            }
        }
        if (!configured.isEmpty() && group.getSelection() == null) {
            group.getElements().nextElement().setSelected(true);
        }

        if (configured.isEmpty()) {
            JLabel empty = new JLabel("<html>No configured domain is available.<br>"
                    + "Open a source in TransformApp, configure it, and Save domain."
                    + "<br>QuizFactory reads "
                    + escapeHtml(DatasetRegistry.defaultFile().getPath()) + ".</html>");
            empty.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            optionsPanel.add(empty);
        }

        JScrollPane optionsScrollPane = new JScrollPane(optionsPanel);
        optionsScrollPane.setPreferredSize(new Dimension(900, 420));

        JPanel buttonPanel = getButtonPanel(optionFont, group, configured, frame);

        frame.add(optionsScrollPane, BorderLayout.CENTER);
        frame.add(buttonPanel);

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setPreferredSize(new Dimension(600, 400));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setResizable(true);
        frame.setVisible(true);
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }

        return s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static JPanel getButtonPanel(
            Font optionFont, ButtonGroup group,
            List<DomainEntry> configured, JFrame selectorFrame) {
        JButton startButton = new JButton("Load selected domain");
        startButton.setFont(optionFont);
        startButton.setEnabled(!configured.isEmpty());

        startButton.addActionListener(e -> {
            if (quizFrame != null && quizFrame.isDisplayable()) {
                quizFrame.setVisible(true);
                quizFrame.toFront();
                quizFrame.requestFocus();
                return;
            }

            if (group.getSelection() == null) return;
            int index = Integer.parseInt(group.getSelection().getActionCommand());
            DomainEntry selected = configured.get(index);
            PREFS.put(PREF_LAST_DOMAIN, selected.name());
            if (!quiz.ui.Dialogs.confirmPersistence(
                    selectorFrame, "Load domain", selected.loadDescription())) return;

            startButton.setText("Loading \"" + selected.name() + "\"…");
            startButton.setEnabled(false);
            new SwingWorker<DomainModel, Void>() {
                @Override protected DomainModel doInBackground() throws Exception {
                    return selected.opener().open();
                }

                @Override protected void done() {
                    try {
                        DomainModel loaded = get();
                        ServedClass servedClass = chooseServedClass(
                                selectorFrame, selected.name(), loaded);
                        if (servedClass == null) {
                            startButton.setText("Load selected domain");
                            return;
                        }
                        quizFrame = new QuizFactory(
                                selected.name(), loaded, servedClass.name()).showQuizzes();
                        startButton.setText("Show \"" + selected.name() + "\"");
                        quizFrame.addWindowListener(new WindowAdapter() {
                            @Override public void windowClosed(WindowEvent event) {
                                quizFrame = null;
                                startButton.setText("Load selected domain");
                                startButton.setEnabled(true);
                            }
                        });
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        JOptionPane.showMessageDialog(selectorFrame,
                                "Could not load domain \"" + selected.name()
                                        + "\":\n" + cause.getMessage(),
                                "Load domain failed", JOptionPane.ERROR_MESSAGE);
                        startButton.setText("Load selected domain");
                    } finally {
                        startButton.setEnabled(true);
                    }
                }
            }.execute();
        });

        JPanel buttonPanel = new JPanel();
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        buttonPanel.add(startButton);
        return buttonPanel;
    }

    public QuizFactory(String domainName, DomainModel domain, String type) {
        this.domainName = domainName;
        this.domain = java.util.Objects.requireNonNull(domain, "domain");
        QuizSource source = sourceFor(domain, type);
        this.type = source.type();
        this.viewables = source.viewables();
        this.configuredGrouping = source.configuredGrouping();
        this.rootView = new GroupView(
                source.root(), domain.configSample(type), domain.fieldTypes(type),
                this::fieldSchemaFor, domain::fieldSchema);
    }

    private objectview.field.FieldSchema fieldSchemaFor(Viewable value) {
        if (value == null) return null;
        String actual = domain.mostSpecificClass(value);
        return domain.fieldSchema(actual == null ? value.typeName() : actual);
    }

    static List<ServedClass> servedClasses(DomainModel domain) {
        if (domain == null) return List.of();
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(
                domain.servedTypes());
        return names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(name -> new ServedClass(name, domain.instancesOf(name).size()))
                .toList();
    }

    private static ServedClass chooseServedClass(
            Component parent, String domainName, DomainModel domain) {
        List<ServedClass> classes = servedClasses(domain);
        if (classes.isEmpty()) {
            JOptionPane.showMessageDialog(parent,
                    "Loaded domain \"" + domainName
                            + "\", but it declares no served classes.",
                    "No served class", JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        Object selected = JOptionPane.showInputDialog(
                parent,
                "Loaded domain \"" + domainName
                        + "\". Select the class whose instances the quiz will use.",
                "Select served class",
                JOptionPane.PLAIN_MESSAGE,
                null,
                classes.toArray(),
                classes.getFirst());
        return selected instanceof ServedClass value ? value : null;
    }

    static QuizSource sourceFor(DomainModel domain, String type) {
        if (domain == null) throw new IllegalArgumentException("Domain is required");
        if (type == null || !domain.servedTypes().contains(type)) {
            throw new IllegalArgumentException(
                    "Class \"" + type + "\" is not served by this domain");
        }
        // Keep a read-only view rather than copying the reference array before the
        // real quiz index is built. History's served classes can exceed 100,000 rows.
        List<Viewable> instances = java.util.Collections.unmodifiableList(
                domain.instancesOf(type));
        if (instances.isEmpty()) {
            throw new IllegalArgumentException(
                    "Class \"" + type + "\" has no saved instances");
        }
        java.util.LinkedHashMap<String, Viewable> byId = new java.util.LinkedHashMap<>();
        for (Viewable instance : instances) {
            if (instance == null || instance.getIdentifier() == null) {
                throw new IllegalArgumentException(
                        "Class \"" + type + "\" contains an instance without an identifier");
            }
            byId.putIfAbsent(instance.getIdentifier(), instance);
        }
        ViewableGroup<?> configuredRoot = domain.groupRoot(type);
        ViewableGroup<?> root = configuredRoot;
        if (root == null) {
            quiz.group.ViewableGroup flat = new quiz.group.ViewableGroup("All " + type);
            instances.forEach(instance -> flat.addMember(instance, false));
            root = flat;
        }
        return new QuizSource(type, instances,
                java.util.Collections.unmodifiableMap(byId), root,
                configuredRoot != null);
    }

    static ViewConfigEditor fieldEditor(
            DomainModel domain, String type, boolean answer) {
        ViewConfig config = new ViewConfig();
        config.setAddListener(!answer);
        config.setThumb(answer);
        Viewable sample = domain.configSample(type);
        if (answer) {
            // An answer starts as the instance's display name alone; other fields
            // are added by ticking them. The question side starts with every field.
            config.setAllFields(false);
            config.addField(displayKey(sample), ViewConfig.leaf());
        }
        // Object fields and their children are independent selections. DISPLAY is an
        // ordinary child field: checking Person.spouse selects the object-field caption
        // only, until the reader explicitly checks spouse.Display label or another
        // nested field.
        ViewConfigEditor editor = new ViewConfigEditor(config, true, sample);
        editor.setConfigRows(config, sample, domain.fieldTypes(type),
                domain.structuralFields(type));
        return editor;
    }

    private static String displayKey(Viewable sample) {
        return sample == null
                ? objectview.field.ViewableContractFieldSet.DISPLAY_KEY
                : objectview.field.ViewableContractFieldSet.displayKey(
                        objectview.field.FieldSet.of(sample));
    }

    /**
     * Keeps question and answer fields disjoint while a quiz type requires it: every
     * field ticked on {@code changed} that is also ticked on {@code other} is unticked
     * on {@code other}, visibly, as unticking its box would.
     *
     * @return the fields unticked on {@code other}
     */
    static List<FieldPath> keepDisjoint(
            ViewConfigEditor changed, ViewConfigEditor other) {
        List<FieldPath> changedFields = changed.selectedFieldPaths();
        List<FieldPath> otherFields = other.selectedFieldPaths();
        Set<FieldPath> overlap = new java.util.LinkedHashSet<>(changedFields);
        overlap.retainAll(new java.util.HashSet<>(otherFields));
        // When nested fields are selected, compare those concrete paths rather than
        // treating their parent object field as a duplicate of every child. Unticking
        // a child leaves the independently selected parent caption intact.
        overlap.removeIf(path -> changed.isObjectFieldPath(path)
                || other.isObjectFieldPath(path));
        List<FieldPath> unticked = new java.util.ArrayList<>();
        for (FieldPath path : overlap) {
            if (other.uncheckFieldPath(path)) unticked.add(path);
        }
        return unticked;
    }

    private ViewConfigEditor fieldEditor(boolean answer) {
        return fieldEditor(domain, type, answer);
    }

    public JFrame showQuizzes() {
        ViewConfigEditor queryEditor = fieldEditor(false);
        ViewConfigEditor answerEditor = fieldEditor(true);

        JPanel queryEditorPanel = new JPanel();
        queryEditorPanel.setLayout(new BoxLayout(queryEditorPanel, BoxLayout.Y_AXIS));
        queryEditorPanel.setBorder(BorderFactory.createTitledBorder("Query fields"));
        queryEditorPanel.add(new JScrollPane(queryEditor));
        JLabel categoryFieldNotice = new JLabel();
        categoryFieldNotice.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        categoryFieldNotice.setVisible(false);
        queryEditorPanel.add(categoryFieldNotice);
        String categorizeUnavailable = quizTypeUnavailableReason(
                QuizAnswerType.CATEGORIZE, type, configuredGrouping);
        if (categorizeUnavailable != null) {
            JLabel noGroups = new JLabel(categorizeUnavailable);
            noGroups.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            queryEditorPanel.add(noGroups);
        }

        JPanel answerEditorPanel = new JPanel();
        answerEditorPanel.setLayout(new BoxLayout(answerEditorPanel, BoxLayout.Y_AXIS));
        answerEditorPanel.setBorder(BorderFactory.createTitledBorder("Answer fields"));
        answerEditorPanel.add(new JScrollPane(answerEditor));

        JSplitPane configSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                queryEditorPanel,
                answerEditorPanel
        );
        configSplit.setResizeWeight(0.5);
        configSplit.setOneTouchExpandable(true);

        JPanel groupPanel = rootView;
        groupPanel.setPreferredSize(new Dimension(300, 800));

        JSplitPane mainSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                configSplit,
                groupPanel
        );
        mainSplit.setResizeWeight(0.7);
        mainSplit.setDividerLocation(0.7);
        mainSplit.setOneTouchExpandable(true);
        mainSplit.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        ButtonGroup group = new ButtonGroup();

        JButton createQuizButton = getCreateQuizButton(
                group, queryEditor, answerEditor, categoryFieldNotice);

        JFrame frame = new JFrame("Quiz - " + domainName + " - " + type);
        frame.setLayout(new BoxLayout(frame.getContentPane(), BoxLayout.Y_AXIS));

        frame.add(mainSplit);

        for (QuizAnswerType type : QuizAnswerType.values()) {
            JRadioButton radioButton = new JRadioButton(type.name());
            radioButton.setActionCommand(type.name());
            String unavailable = quizTypeUnavailableReason(
                    type, this.type, configuredGrouping);
            if (unavailable != null) {
                radioButton.setEnabled(false);
                radioButton.setToolTipText(unavailable);
            }
            radioButton.addActionListener(event -> {
                updateCategoryFieldExclusion(group, queryEditor, categoryFieldNotice);
                // Choosing a type that needs disjoint fields resolves an existing
                // overlap in favour of the answer, the smaller deliberate choice.
                if (requiresDisjoint(group)) keepDisjoint(answerEditor, queryEditor);
            });
            group.add(radioButton);
            frame.add(radioButton);
        }

        // Ticking a field on one side unticks it on the other while the chosen quiz
        // type needs disjoint question and answer fields.
        queryEditor.setChangeListener(() -> {
            if (requiresDisjoint(group)) keepDisjoint(queryEditor, answerEditor);
        });
        answerEditor.setChangeListener(() -> {
            if (requiresDisjoint(group)) keepDisjoint(answerEditor, queryEditor);
        });

        rootView.setSelectionHandler(selected -> updateCategoryFieldExclusion(
                group, queryEditor, categoryFieldNotice));

        frame.add(createQuizButton);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setPreferredSize(new Dimension(1400, 900));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setResizable(true);
        frame.setVisible(true);

        return frame;
    }

    static String quizTypeUnavailableReason(
            QuizAnswerType quizType, String className, boolean configuredGrouping) {
        return quizType == QuizAnswerType.CATEGORIZE && !configuredGrouping
                ? "Categorize is unavailable: class \"" + className
                        + "\" has no saved group root."
                : null;
    }

    private void updateCategoryFieldExclusion(
            ButtonGroup quizTypes,
            ViewConfigEditor queryEditor,
            JLabel notice) {
        Set<FieldPath> excluded = categorizeExcludedFields(
                selectedQuizType(quizTypes), rootView.selectedGroup());
        queryEditor.setExcludedFieldPaths(excluded);
        if (excluded.isEmpty()) {
            notice.setText("");
            notice.setVisible(false);
        } else {
            String fields = excluded.stream().map(FieldPath::dotted)
                    .collect(java.util.stream.Collectors.joining(", "));
            notice.setText("Categorize excludes the category field from questions: "
                    + fields);
            notice.setVisible(true);
        }
    }

    private static boolean requiresDisjoint(ButtonGroup group) {
        QuizAnswerType type = selectedQuizType(group);
        return type != null && type.requiresDisjointQuestionAndAnswerFields();
    }

    private static QuizAnswerType selectedQuizType(ButtonGroup group) {
        return group == null || group.getSelection() == null ? null
                : QuizAnswerType.valueOf(group.getSelection().getActionCommand());
    }

    static Set<FieldPath> categorizeExcludedFields(
            QuizAnswerType type,
            objectview.group.ViewableGroup<?> selectedGroup) {
        return type == QuizAnswerType.CATEGORIZE
                ? quiz.transform.FacetGroup.fieldPathOf(selectedGroup)
                        .map(Set::of).orElseGet(Set::of)
                : Set.of();
    }

    static String fieldSelectionProblem(
            QuizAnswerType type,
            java.util.Collection<FieldPath> questionFields,
            java.util.Collection<FieldPath> answerFields) {
        return type != null && type.requiresDisjointQuestionAndAnswerFields()
                ? Quiz.disjointFieldProblem(questionFields, answerFields)
                : null;
    }

    private JButton getCreateQuizButton(ButtonGroup group,
                                        ViewConfigEditor queryEditor,
                                        ViewConfigEditor answerEditor,
                                        JLabel categoryFieldNotice) {
        JButton createQuizButton = new JButton("Create quiz");
        createQuizButton.addActionListener(e -> {
            DefaultMutableTreeNode node =
                    (DefaultMutableTreeNode) rootView.getTree().getLastSelectedPathComponent();
            if (group.getSelection() == null) {
                JOptionPane.showMessageDialog(quizFrame, "Quiz type is not selected.");
                return;
            }
            ViewableGroup<?> selectedGroup = node == null ? null
                    : rootView.getViewableGroup(node);

            // Read the editor only after applying the current quiz/group policy. This
            // keeps creation and the visible list of allowed fields on one answer.
            updateCategoryFieldExclusion(
                    group, queryEditor, categoryFieldNotice);

            ViewConfig queryConfig = queryEditor.getConfig().copy();
            ViewConfig answerConfig = answerEditor.getConfig().copy();

            QuizAnswerType answerType = QuizAnswerType.valueOf(
                    group.getSelection().getActionCommand());
            String overlap = fieldSelectionProblem(
                    answerType, queryEditor.selectedFieldPaths(),
                    answerEditor.selectedFieldPaths());
            if (overlap != null) {
                JOptionPane.showMessageDialog(
                        quizFrame, overlap, "Question and answer fields overlap",
                        JOptionPane.WARNING_MESSAGE);
                return;
            }

            queryConfig.setAddListener(false);
            answerConfig.setAddListener(false);

            Quiz quiz;
            try {
                quiz = createQuiz(queryConfig, answerConfig, answerType,
                        selectedGroup, viewables);
            } catch (RuntimeException failure) {
                showQuizCreationFailure(failure);
                return;
            }
            process.swing.SwingActionRunner.run(
                    createQuizButton, "Preparing quiz…", quizFrame,
                    quiz::prepareQuiz,
                    problem -> {
                        if (problem == null) quiz.show();
                        else JOptionPane.showMessageDialog(quizFrame, problem);
                    },
                    this::showQuizCreationFailure);
        });
        return createQuizButton;
    }

    private void showQuizCreationFailure(Throwable failure) {
        JOptionPane.showMessageDialog(quizFrame,
                "Could not create quiz:\n" + failure.getMessage(),
                "Create quiz failed", JOptionPane.ERROR_MESSAGE);
    }

    private Quiz createQuiz(ViewConfig queryConfig,
                            ViewConfig answerConfig,
                            QuizAnswerType answerType,
                            ViewableGroup<?> selectedGroup,
                            Map<String, ? extends Viewable> viewables) {
        Quiz quiz = switch (answerType) {
            case ABCD, LIST ->
                    new QuizListABCD(queryConfig, answerConfig, answerType,
                                     selectedGroup, viewables, true);
            case PAIRING ->
                    new QuizPairs(queryConfig, answerConfig, selectedGroup,
                                  viewables, true);
            case CATEGORIZE -> new QuizCategorize(queryConfig, selectedGroup,
                                                  viewables, true);
            case SIXDEGREES -> new QuizSixDegrees(queryConfig, selectedGroup,
                                                  viewables, true);
            default -> throw new IllegalArgumentException();
        };
        quiz.setSchemas(this::fieldSchemaFor, domain::fieldSchema);
        return quiz;
    }
}

enum QuizAnswerType {
    LIST(true),
    ABCD(true),
    PAIRING(true),
    CATEGORIZE(false),
    SIXDEGREES(false);

    private final boolean disjointQuestionAndAnswerFields;

    QuizAnswerType(boolean disjointQuestionAndAnswerFields) {
        this.disjointQuestionAndAnswerFields = disjointQuestionAndAnswerFields;
    }

    boolean requiresDisjointQuestionAndAnswerFields() {
        return disjointQuestionAndAnswerFields;
    }
}
//    INTERSECTION
