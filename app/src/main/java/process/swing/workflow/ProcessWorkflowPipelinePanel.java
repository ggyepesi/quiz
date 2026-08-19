package process.swing.workflow;

import process.ProcessWorkflowPipeline;
import process.PhaseExplanation;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.LinkedHashMap;
import java.util.Map;

/** Interactive, compact block view of a {@link ProcessWorkflowPipeline}. */
public final class ProcessWorkflowPipelinePanel extends JPanel {
    private final ProcessWorkflowPipeline pipeline;
    private final JPanel graph = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
    private final JPanel details = new JPanel();
    private final Map<String, JButton> blocks = new LinkedHashMap<>();
    private final Timer elapsedClock = new Timer(1_000, e -> refresh());
    private String selectedId;
    private java.util.function.Consumer<PhaseExplanation.ModelReference> navigateReference =
            reference -> { };

    public ProcessWorkflowPipelinePanel(ProcessWorkflowPipeline pipeline) {
        super(new BorderLayout(8, 8));
        this.pipeline = pipeline;
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                new JScrollPane(graph), new JScrollPane(details));
        split.setResizeWeight(0.68);
        split.setDividerLocation(0.68);
        add(split, BorderLayout.CENTER);
        setPreferredSize(new Dimension(900, 500));

        pipeline.addListener(() -> SwingUtilities.invokeLater(this::refresh));
        refresh();
    }

    /** Routes a class/field/property chip into the host application's Explorer. */
    public void onNavigateReference(
            java.util.function.Consumer<PhaseExplanation.ModelReference> handler) {
        navigateReference = handler == null ? reference -> { } : handler;
    }

    @Override public void addNotify() {
        super.addNotify();
        elapsedClock.start();
    }

    @Override public void removeNotify() {
        elapsedClock.stop();
        super.removeNotify();
    }

    private void refresh() {
        var snapshot = pipeline.snapshot();
        if (blocks.isEmpty()) {
            for (int i = 0; i < snapshot.size(); i++) {
                ProcessWorkflowPipeline.Phase phase = snapshot.get(i).phase();
                JButton block = new JButton();
                block.setPreferredSize(new Dimension(190, 72));
                block.addActionListener(e -> select(phase.id()));
                blocks.put(phase.id(), block);
                graph.add(block);
                if (i + 1 < snapshot.size()) graph.add(new JLabel("→"));
            }
        }
        if (selectedId == null && !snapshot.isEmpty()) {
            ProcessWorkflowPipeline.PhaseState active = snapshot.stream()
                    .filter(s -> s.status() == ProcessWorkflowPipeline.Status.RUNNING)
                    .findFirst().orElse(snapshot.get(0));
            selectedId = active.phase().id();
        }
        for (ProcessWorkflowPipeline.PhaseState state : snapshot) {
            JButton block = blocks.get(state.phase().id());
            block.setText(html(state));
            block.setBackground(color(state.status()));
            block.setOpaque(true);
            block.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(state.phase().id().equals(selectedId)
                            ? new Color(35, 90, 170) : color(state.status()).darker(),
                            state.phase().id().equals(selectedId) ? 3 : 1),
                    BorderFactory.createEmptyBorder(5, 7, 5, 7)));
        }
        ProcessWorkflowPipeline.PhaseState selected = snapshot.stream()
                .filter(s -> s.phase().id().equals(selectedId)).findFirst().orElse(null);
        if (selected != null) showExplanation(selected);
        graph.revalidate();
        graph.repaint();
    }

    private void select(String id) {
        selectedId = id;
        refresh();
    }

    private static String html(ProcessWorkflowPipeline.PhaseState state) {
        String elapsed = isTimed(state) ? " · " + formatElapsed(state.elapsedMillis()) : "";
        String summary = state.summary().isBlank() ? "" : "<br><small>"
                + escape(shorten(state.summary(), 55)) + "</small>";
        return "<html><center><b>" + escape(state.phase().title()) + "</b><br>"
                + state.status() + elapsed + summary + "</center></html>";
    }

    private void showExplanation(ProcessWorkflowPipeline.PhaseState state) {
        details.removeAll();
        JLabel title = new JLabel("<html><h2>" + escape(state.phase().title())
                + "</h2></html>");
        title.setAlignmentX(LEFT_ALIGNMENT);
        details.add(title);

        JPanel run = new JPanel(new GridLayout(0, 1, 2, 2));
        run.setAlignmentX(LEFT_ALIGNMENT);
        run.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color(state.status()).darker()),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        run.setBackground(color(state.status()));
        run.add(new JLabel("Status: " + state.status()
                + (isTimed(state) ? " · " + formatElapsed(state.elapsedMillis()) : "")));
        if (!state.summary().isBlank()) run.add(wrapped("Run: " + state.summary()));
        details.add(run);
        details.add(Box.createVerticalStrut(8));

        PhaseExplanation explanation = state.phase().explanation();
        String purpose = explanation.isEmpty()
                ? state.phase().description() : explanation.purpose();
        addTextSection("Why", purpose);
        addListSection("Consumes", explanation.inputs());
        addListSection("What happens", explanation.operations().isEmpty()
                ? state.phase().details() : explanation.operations());
        addListSection("Produces", explanation.outputs());
        addReferences(explanation.references());
        addExamples(explanation.examples());

        details.add(Box.createVerticalGlue());
        details.revalidate();
        details.repaint();
    }

    private void addTextSection(String heading, String text) {
        if (text == null || text.isBlank()) return;
        addHeading(heading);
        JLabel label = wrapped(text);
        label.setAlignmentX(LEFT_ALIGNMENT);
        details.add(label);
        details.add(Box.createVerticalStrut(7));
    }

    private void addListSection(String heading, java.util.List<String> values) {
        if (values == null || values.isEmpty()) return;
        addHeading(heading);
        for (String value : values) {
            JLabel label = wrapped("• " + value);
            label.setAlignmentX(LEFT_ALIGNMENT);
            details.add(label);
            details.add(Box.createVerticalStrut(2));
        }
        details.add(Box.createVerticalStrut(5));
    }

    private void addReferences(java.util.List<PhaseExplanation.ModelReference> refs) {
        if (refs == null || refs.isEmpty()) return;
        addHeading("Configuration");
        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        chips.setAlignmentX(LEFT_ALIGNMENT);
        for (PhaseExplanation.ModelReference reference : refs) {
            JButton chip = new JButton(reference.label());
            chip.setMargin(new java.awt.Insets(2, 6, 2, 6));
            chip.setToolTipText(reference.kind() + " — open in Explorer");
            chip.addActionListener(e -> navigateReference.accept(reference));
            chips.add(chip);
        }
        details.add(chips);
        details.add(Box.createVerticalStrut(5));
    }

    private void addExamples(java.util.List<PhaseExplanation.PhaseExample> examples) {
        if (examples == null || examples.isEmpty()) return;
        addHeading("Examples");
        for (PhaseExplanation.PhaseExample example : examples) {
            JPanel card = new JPanel();
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setAlignmentX(LEFT_ALIGNMENT);
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(190, 195, 202)),
                    BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            JLabel name = new JLabel("<html><b>" + escape(example.title())
                    + "</b> <font color='#666666'>" + example.kind() + "</font></html>");
            name.setAlignmentX(LEFT_ALIGNMENT);
            card.add(name);
            addExamplePart(card, "Input", example.input());
            addExamplePart(card, "Evidence", example.evidence());
            addExamplePart(card, "Result", example.output());
            if (!example.references().isEmpty()) {
                JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
                chips.setAlignmentX(LEFT_ALIGNMENT);
                for (PhaseExplanation.ModelReference reference : example.references()) {
                    JButton chip = new JButton(reference.label());
                    chip.setMargin(new java.awt.Insets(1, 5, 1, 5));
                    chip.addActionListener(e -> navigateReference.accept(reference));
                    chips.add(chip);
                }
                card.add(chips);
            }
            details.add(card);
            details.add(Box.createVerticalStrut(6));
        }
    }

    private static void addExamplePart(JPanel card, String title, java.util.List<String> rows) {
        if (rows == null || rows.isEmpty()) return;
        card.add(Box.createVerticalStrut(4));
        JLabel heading = new JLabel("<html><b>" + escape(title) + "</b></html>");
        heading.setAlignmentX(LEFT_ALIGNMENT);
        card.add(heading);
        for (String row : rows) {
            JLabel label = wrapped("• " + row);
            label.setAlignmentX(LEFT_ALIGNMENT);
            card.add(label);
        }
    }

    private void addHeading(String text) {
        JLabel heading = new JLabel("<html><b>" + escape(text) + "</b></html>");
        heading.setAlignmentX(LEFT_ALIGNMENT);
        details.add(heading);
        details.add(Box.createVerticalStrut(3));
    }

    private static JLabel wrapped(String text) {
        JLabel label = new JLabel("<html><div style='width:310px'>"
                + escape(text) + "</div></html>");
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        return label;
    }

    private static boolean isTimed(ProcessWorkflowPipeline.PhaseState state) {
        return state.status() != ProcessWorkflowPipeline.Status.PENDING
                && state.startedAtNanos() > 0;
    }

    static String formatElapsed(long elapsedMillis) {
        if (elapsedMillis < 1_000) return "<1s";
        long seconds = elapsedMillis / 1_000;
        long hours = seconds / 3_600;
        long minutes = seconds % 3_600 / 60;
        long remainder = seconds % 60;
        if (hours > 0) return "%dh %02dm %02ds".formatted(hours, minutes, remainder);
        if (minutes > 0) return "%dm %02ds".formatted(minutes, remainder);
        return seconds + "s";
    }

    private static Color color(ProcessWorkflowPipeline.Status status) {
        return switch (status) {
            case PENDING -> new Color(225, 228, 232);
            case RUNNING -> new Color(130, 185, 245);
            case COMPLETED -> new Color(145, 210, 155);
            case PARTIAL -> new Color(245, 190, 105);
            case FAILED -> new Color(235, 125, 125);
            case CANCELLED -> new Color(185, 180, 195);
        };
    }

    private static String shorten(String value, int max) {
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;");
    }
}
