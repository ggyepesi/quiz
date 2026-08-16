package process.swing.workflow;

import process.ProcessWorkflowPipeline;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.Map;

/** Interactive, compact block view of a {@link ProcessWorkflowPipeline}. */
final class ProcessWorkflowPipelinePanel extends JPanel {
    private final ProcessWorkflowPipeline pipeline;
    private final JPanel graph = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
    private final JTextArea details = new JTextArea();
    private final Map<String, JButton> blocks = new LinkedHashMap<>();
    private final Timer elapsedClock = new Timer(1_000, e -> refresh());
    private String selectedId;

    ProcessWorkflowPipelinePanel(ProcessWorkflowPipeline pipeline) {
        super(new BorderLayout(8, 8));
        this.pipeline = pipeline;
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
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
        if (selected != null) details.setText(detailText(selected));
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

    private static String detailText(ProcessWorkflowPipeline.PhaseState state) {
        StringBuilder out = new StringBuilder();
        out.append(state.phase().title()).append('\n')
                .append("Status: ").append(state.status()).append('\n');
        if (isTimed(state)) {
            out.append("Elapsed: ").append(formatElapsed(state.elapsedMillis())).append('\n');
        }
        out.append('\n')
                .append(state.phase().description()).append('\n');
        if (!state.summary().isBlank()) {
            out.append("\nRun: ").append(state.summary()).append('\n');
        }
        if (!state.phase().details().isEmpty()) {
            out.append("\nConfigured operations\n");
            for (String detail : state.phase().details()) {
                out.append("  • ").append(detail).append('\n');
            }
        }
        return out.toString();
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
