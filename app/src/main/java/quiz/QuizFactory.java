package quiz;

import aux.Constants;
import flag.SportTeam;
import flag.SportTeams;
import flag.State;
import flag.States;
import language.Language;
import language.Languages;
import mythology.Creature;
import mythology.MythologyEntities;
import nobel.NobelPrize;
import nobel.NobelPrizes;
import objectview.viewconfig.DomainViews;
import objectview.viewconfig.ViewConfig;
import oscar.OscarNomination;
import oscar.OscarNominations;
import presidents.President;
import presidents.USPresidents;
import objectview.media.ImageBlurrer;
import objectview.render.GroupView;
import objectview.viewconfig.ViewConfigEditor;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Map;
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

    private record QuizOption(
            String icon,
            String name,
            DomainViews views,
            Class<? extends QuizableAdapter> cls
    ) {}

    private static final List<QuizOption> quizOptions = List.of(
            new QuizOption("🐉", "Mythology", new MythologyEntities(), Creature.class),
            new QuizOption("🏳️", "States", new States(), State.class),
            new QuizOption("⚽", "Sport Teams", new SportTeams(), SportTeam.class),
            new QuizOption("🏅", "Nobel Prizes", new NobelPrizes(), NobelPrize.class),
            new QuizOption("🇺🇸", "US Presidents", new USPresidents(), President.class),
            new QuizOption("🗣️", "Languages", new Languages(), Language.class),
            new QuizOption("🗣️", "Oscars", new OscarNominations(), OscarNomination.class)
    );

    /** A built-in Quizable domain (icon, name, builder) — exposed so the transform
     *  domain navigator (and later the web server) can offer the same domains as
     *  the quiz, alongside the generated Wikidata datasets. */
    public record BuiltInDomain(String icon, String name, DomainViews views) {}

    public static List<BuiltInDomain> builtInDomains() {
        List<BuiltInDomain> out = new java.util.ArrayList<>();
        for (QuizOption o : quizOptions) {
            out.add(new BuiltInDomain(o.icon(), o.name(), o.views()));
        }
        return out;
    }

    private static final String PREF_LAST_QUIZ = "lastQuizIndex";
    private static final Preferences PREFS =
            Preferences.userNodeForPackage(QuizFactory.class);

    private static Class<? extends QuizableAdapter> cls;
    private static JFrame quizFrame;

    private final Map<String, ? extends Quizable> quizables;
    private final GroupView rootView;
    private final DomainViews qvs;

    public static void main(String[] args) {
        Constants.setFontSizeMultiplier(1.5f);
        SwingUtilities.invokeLater(QuizFactory::showQuizSelector);
    }

    private static void showQuizSelector() {
        JFrame frame = new JFrame("Select Quiz Source");
        frame.setLayout(new BoxLayout(frame.getContentPane(), BoxLayout.Y_AXIS));

        ButtonGroup group = new ButtonGroup();

        JPanel optionsPanel = new JPanel();
        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
        optionsPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        int lastIndex = PREFS.getInt(PREF_LAST_QUIZ, 0);
        if (lastIndex < 0 || lastIndex >= quizOptions.size()) {
            lastIndex = 0;
        }

        java.awt.Font optionFont = new java.awt.Font(
                java.awt.Font.SANS_SERIF,
                java.awt.Font.PLAIN,
                22
        );

        for (int i = 0; i < quizOptions.size(); i++) {
            QuizOption option = quizOptions.get(i);
            String label = option.icon() + "  " + option.name();
            JRadioButton radioButton = new JRadioButton(label);
            radioButton.setFont(optionFont);
            radioButton.setActionCommand(String.valueOf(i));
            radioButton.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            group.add(radioButton);
            optionsPanel.add(radioButton);

            if (i == lastIndex) {
                radioButton.setSelected(true);
            }
        }

        JScrollPane optionsScrollPane = new JScrollPane(optionsPanel);
        optionsScrollPane.setPreferredSize(new Dimension(900, 420));

        JPanel buttonPanel = getButtonPanel(optionFont, group);

        frame.add(optionsScrollPane, BorderLayout.CENTER);
        frame.add(buttonPanel);

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setPreferredSize(new Dimension(600, 400));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setResizable(true);
        frame.setVisible(true);
    }

    private JRadioButton optionButton(String text) {
        JRadioButton b = new JRadioButton("<html><div style='width:700px;'>"
                                                  + escapeHtml(text)
                                                  + "</div></html>");
        b.setVerticalAlignment(SwingConstants.TOP);
        return b;
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

    private static JPanel getButtonPanel(Font optionFont, ButtonGroup group) {
        JButton startButton = new JButton("Start");
        startButton.setFont(optionFont);

        startButton.addActionListener(e -> {
            if (quizFrame != null && quizFrame.isDisplayable()) {
                quizFrame.setVisible(true);
                quizFrame.toFront();
                quizFrame.requestFocus();
                return;
            }

            int index = Integer.parseInt(group.getSelection().getActionCommand());
            PREFS.putInt(PREF_LAST_QUIZ, index);

            QuizOption selected = quizOptions.get(index);
            cls = selected.cls();

            startButton.setText("Starting...");
            startButton.setEnabled(false);

            SwingUtilities.invokeLater(() -> {
                try {
                    quizFrame = new QuizFactory(selected.views()).showQuizzes();

                    startButton.setText("Show");

                    quizFrame.addWindowListener(new WindowAdapter() {
                        @Override
                        public void windowClosed(WindowEvent e) {
                            quizFrame = null;
                            startButton.setText("Start");
                            startButton.setEnabled(true);
                        }
                    });
                } catch (Exception ex) {
                    ex.printStackTrace();
                    startButton.setText("Start");
                } finally {
                    startButton.setEnabled(true);
                }
            });
        });

        JPanel buttonPanel = new JPanel();
        buttonPanel.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));
        buttonPanel.add(startButton);
        return buttonPanel;
    }

    public QuizFactory(DomainViews qvs) throws Exception {
        this.qvs = qvs;
        qvs.buildViews();
        rootView = qvs.getGroupView();
        // getViewables() is typed Viewable (objectview SPI); every element is in fact a
        // Quizable here, so narrow it for the quiz-side generation code.
        @SuppressWarnings("unchecked")
        Map<String, ? extends Quizable> qz =
                (Map<String, ? extends Quizable>) (Map<String, ?>) qvs.getViewables();
        quizables = qz;

        //System.out.println("Create QuizableFilterCollectionFrame");
        //new QuizableFilterCollectionFrame(quizables.values(), cls);
    }

    public JFrame showQuizzes() {
        ViewConfig queryConfig = ViewConfig.of(cls)
                                           .initializeAllFields(true)
                                           .setAddListener(true);       // or false if you don’t want UI listeners
        ViewConfigEditor queryEditor = new ViewConfigEditor(queryConfig);

        ViewConfig answerConfig = ViewConfig.of(cls)
                                            .initializeAllFields(true)
                                            .setAddListener(false)
                                            .setThumb(true);
        ViewConfigEditor answerEditor = new ViewConfigEditor(answerConfig);

        JPanel queryEditorPanel = new JPanel();
        queryEditorPanel.setLayout(new BoxLayout(queryEditorPanel, BoxLayout.Y_AXIS));
        queryEditorPanel.setBorder(BorderFactory.createTitledBorder("Query fields"));
        queryEditorPanel.add(new JScrollPane(queryEditor));

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

        JPanel groupPanel = rootView.getMainPanel();
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

        JButton createQuizButton = getCreateQuizButton(group, queryEditor, answerEditor);

        JFrame frame = new JFrame("Quiz - " + qvs.getClass().getSimpleName());
        frame.setLayout(new BoxLayout(frame.getContentPane(), BoxLayout.Y_AXIS));

        frame.add(mainSplit);

        for (QuizAnswerType type : QuizAnswerType.values()) {
            JRadioButton radioButton = new JRadioButton(type.name());
            radioButton.setActionCommand(type.name());
            group.add(radioButton);
            frame.add(radioButton);
        }

        frame.add(createQuizButton);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setPreferredSize(new Dimension(1400, 900));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setResizable(true);
        frame.setVisible(true);

        return frame;
    }

    private JButton getCreateQuizButton(ButtonGroup group,
                                        ViewConfigEditor queryEditor,
                                        ViewConfigEditor answerEditor) {
        JButton createQuizButton = new JButton("Create quiz");
        createQuizButton.addActionListener(e -> {
            DefaultMutableTreeNode node =
                    (DefaultMutableTreeNode) rootView.getTree().getLastSelectedPathComponent();
            if (group.getSelection() == null) {
                JOptionPane.showMessageDialog(quizFrame, "Quiz type is not selected.");
                return;
            }
            QuizableGroup selectedGroup = node == null ? null : (QuizableGroup) rootView.getViewableGroup(node);

            ViewConfig queryConfig = queryEditor.getConfig().copy();
            ViewConfig answerConfig = answerEditor.getConfig().copy();

            queryConfig.setAddListener(false);
            answerConfig.setAddListener(false);

            QuizAnswerType answerType = QuizAnswerType.valueOf(group.getSelection().getActionCommand());

            SwingUtilities.invokeLater(() ->
                    showQuiz(queryConfig, answerConfig, answerType,
                          selectedGroup, quizables)
            );
        });
        return createQuizButton;
    }

    private void showQuiz(ViewConfig queryConfig,
                          ViewConfig answerConfig,
                          QuizAnswerType answerType,
                          QuizableGroup selectedGroup,
                          Map<String, ? extends Quizable> quizables) {
        Quiz quiz = createQuiz(queryConfig, answerConfig, answerType,
                               selectedGroup, quizables);
        String message = quiz.prepareQuiz();
        System.out.println("PREPARE " + message);
        if (message == null) {
            quiz.show();
        } else {
            JOptionPane.showMessageDialog(quizFrame, message);
        }
    }

    private Quiz createQuiz(ViewConfig queryConfig,
                            ViewConfig answerConfig,
                            QuizAnswerType answerType,
                            QuizableGroup selectedGroup,
                            Map<String, ? extends Quizable> quizables) {
        return switch (answerType) {
            case ABCD, LIST ->
                    new QuizListABCD(queryConfig, answerConfig, answerType,
                                     selectedGroup, quizables);
            case PAIRING ->
                    new QuizPairs(queryConfig, answerConfig, selectedGroup,
                                  quizables);
            case CATEGORIZE -> new QuizCategorize(queryConfig, selectedGroup,
                                                  quizables);
            case SIXDEGREES -> new QuizSixDegrees(queryConfig, selectedGroup,
                                                  quizables);
            default -> throw new IllegalArgumentException();
        };
    }
}

enum QuizAnswerType {
    LIST,
    ABCD,
    PAIRING,
    CATEGORIZE,
    SIXDEGREES
}
//    INTERSECTION
