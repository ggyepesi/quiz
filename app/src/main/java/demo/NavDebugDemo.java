package demo;

import objectview.demo.MultiView;
import quiz.QuizableAdapter;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A small, controlled harness for debugging reference navigation in the
 * virtualized card list — instead of guessing against the real datasets.
 *
 * <p>Two artificial classes ({@link Alpha}, {@link Beta}), 5 instances each, in
 * two side-by-side sections sharing one render context. Every instance has:
 * <ul>
 *   <li>a <b>referring</b> field ({@code partner}) pointing at a top-level
 *       instance of the OTHER class — renders as a chip that NAVIGATES (scroll
 *       to + flash the target's card);</li>
 *   <li>an <b>expandable</b> field ({@code note}) pointing at a {@link Note}
 *       that is NOT registered top-level — renders as a chip that EXPANDS in
 *       place, which CHANGES the card's height (the thing that shifts cumulative
 *       offsets and is the suspected cause of the intermittent mis-navigation).</li>
 * </ul>
 *
 * The {@code blurb} lines make each card tall enough that 5 cards exceed a short
 * window, so navigation requires real scrolling.
 *
 * <p>Run with {@code -Dquiz.nav.debug=true} to print, on every chip click, the
 * computed target index / offset and where the card actually landed:
 * <pre>
 *   mvn -q compile
 *   java -Dquiz.nav.debug=true -cp target/classes demo.NavDebugDemo
 * </pre>
 *
 * Repro recipe: navigate to the last card (works), then EXPAND an earlier card
 * (its height grows), then navigate to the last card again — watch whether the
 * trace shows {@code tops[i]} shifting and whether the final {@code cardY}
 * matches {@code viewPos.y} (i.e. the card actually reaches the top).
 */
public final class NavDebugDemo {

    /** A leaf Quizable used only as an EXPANDABLE chip (never top-level). */
    public static final class Note extends QuizableAdapter {
        public String text = "";

        public Note() {}

        public Note(String text) {
            this.text = text;
        }

        @Override public String getIdentifier() { return text; }
        @Override public String getDisplayName() { return text; }
        @Override public String toString() { return text; }
    }

    public static final class Alpha extends QuizableAdapter {
        public String name = "";
        public Beta partner;          // referring (top-level) -> navigate
        public Note note;             // expandable (not top-level) -> expand in place
        public String blurb = "";

        public Alpha() {}

        public Alpha(String name) { this.name = name; }

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
        @Override public String toString() { return name; }
    }

    public static final class Beta extends QuizableAdapter {
        public String name = "";
        public Alpha partner;         // referring (top-level) -> navigate
        public Note note;             // expandable (not top-level) -> expand in place
        public String blurb = "";

        public Beta() {}

        public Beta(String name) { this.name = name; }

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
        @Override public String toString() { return name; }
    }

    public static void main(String[] args) {
        // Force nav debug on for this demo (read once when VirtualizedCardList
        // loads, which happens later during launch) so [nav] prints even when
        // launched from a toolbar/IDE without the -Dquiz.nav.debug VM arg.
        if (System.getProperty("quiz.nav.debug") == null) {
            System.setProperty("quiz.nav.debug", "true");
        }
        SwingUtilities.invokeLater(NavDebugDemo::launch);
    }

    private static void launch() {
        // Count per section — pass as the first arg, else default. The bug is
        // suspected to need thousands of (estimated-height, never-built) cards so
        // the cumulative-offset estimate can drift far from a card's true position.
        int count = countArg();
        List<Alpha> alphas = new ArrayList<>();
        List<Beta> betas = new ArrayList<>();

        for (int i = 1; i <= count; i++) {
            Alpha a = new Alpha("Alpha-" + i);
            a.note = new Note("note about Alpha-" + i + " — click to expand");
            a.blurb = blurb("Alpha", i);
            alphas.add(a);

            Beta b = new Beta("Beta-" + i);
            b.note = new Note("note about Beta-" + i + " — click to expand");
            b.blurb = blurb("Beta", i);
            betas.add(b);
        }

        // Cross-link so a click jumps FAR (half the list away) into the other
        // section — i.e. through thousands of unmeasured cards.
        for (int i = 0; i < count; i++) {
            alphas.get(i).partner = betas.get((i + count / 2) % count);
            betas.get(i).partner = alphas.get((i + count / 2 + 1) % count);
        }

        MultiView mv = new MultiView();
        mv.addSection("Alphas", Alpha.class, alphas);
        mv.addSection("Betas", Beta.class, betas);
        mv.build(1);

        JFrame frame = new JFrame(
                "NavDebugDemo (" + count + "/section) — click a partner chip to navigate; "
                        + "expand a note to grow a card"
                        + (Boolean.getBoolean("quiz.nav.debug") ? "  [nav debug ON]" : ""));
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(recordBar(mv), BorderLayout.NORTH);
        frame.add(mv, BorderLayout.CENTER);
        // Tall enough to scroll through, short enough that the target must be
        // scrolled to (a handful of cards visible out of thousands).
        frame.setSize(1100, 700);
        // FIXED position (not centred) so recorded screen coordinates replay
        // identically in a fresh instance.
        frame.setLocation(60, 60);
        frame.setVisible(true);
    }

    // Record / replay toolbar: capture reference clicks and replay them in a fresh
    // instance to reproduce the intermittent navigation failure deterministically.
    private static java.awt.Component recordBar(java.awt.Component scope) {
        ClickRecorder recorder = new ClickRecorder();
        JButton record = new JButton("● Record");
        JButton stopSave = new JButton("■ Stop + Save…");
        JButton replay = new JButton("▶ Replay…");
        javax.swing.JLabel status = new javax.swing.JLabel(" ");
        stopSave.setEnabled(false);

        record.addActionListener(e -> {
            recorder.start(scope);
            record.setEnabled(false);
            stopSave.setEnabled(true);
            status.setText("Recording clicks in the card area…");
        });

        stopSave.addActionListener(e -> {
            recorder.stop();
            record.setEnabled(true);
            stopSave.setEnabled(false);
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
            fc.setSelectedFile(new java.io.File("nav-clicks.txt"));
            if (fc.showSaveDialog(scope) == javax.swing.JFileChooser.APPROVE_OPTION) {
                try {
                    recorder.save(fc.getSelectedFile());
                    status.setText("Saved " + recorder.size() + " events to "
                            + fc.getSelectedFile().getName());
                } catch (Exception ex) {
                    status.setText("Save failed: " + ex.getMessage());
                }
            } else {
                status.setText("Recorded " + recorder.size() + " events (not saved).");
            }
        });

        replay.addActionListener(e -> {
            javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
            fc.setSelectedFile(new java.io.File("nav-clicks.txt"));
            if (fc.showOpenDialog(scope) == javax.swing.JFileChooser.APPROVE_OPTION) {
                try {
                    ClickReplayer.replay(fc.getSelectedFile(),
                            msg -> javax.swing.SwingUtilities.invokeLater(
                                    () -> status.setText(msg)));
                } catch (Exception ex) {
                    status.setText("Replay failed: " + ex.getMessage());
                }
            }
        });

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(record);
        bar.add(stopSave);
        bar.add(replay);
        bar.addSeparator();
        bar.add(status);
        return bar;
    }

    private static int countArg() {
        String prop = System.getProperty("count");
        if (prop != null) {
            try {
                return Math.max(2, Integer.parseInt(prop.trim()));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 3000;
    }

    private static String blurb(String kind, int i) {
        StringBuilder sb = new StringBuilder();
        for (int line = 1; line <= 3; line++) {
            sb.append(kind).append('-').append(i)
              .append(" filler line ").append(line)
              .append(": some wrapping text so the card is tall enough that five "
                      + "cards overflow the short viewport and navigation must scroll. ");
        }
        return sb.toString();
    }

    private NavDebugDemo() {}
}
