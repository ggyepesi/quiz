package quiz.ui;

import quiz.round.RoundProgress;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.ActionListener;
import java.util.Objects;

/**
 * Reusable desktop frame for one quiz round: progress and future player/score
 * context at the top, mode-specific content in the center, and one controlled
 * advance action at the bottom.
 */
public final class RoundShell extends JPanel {
    private final JLabel progressLabel = new JLabel();
    private final JLabel playerLabel = new JLabel();
    private final JLabel scoreLabel = new JLabel();
    private final JPanel contentHost = new JPanel(new BorderLayout());
    private final JButton advanceButton = new JButton("Next");

    public RoundShell() {
        setLayout(new BorderLayout(0, 10));
        setBorder(BorderFactory.createEmptyBorder(12, 16, 16, 16));

        progressLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        playerLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        scoreLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        playerLabel.setVisible(false);
        scoreLabel.setVisible(false);

        JPanel context = new JPanel();
        context.setLayout(new BoxLayout(context, BoxLayout.X_AXIS));
        context.add(playerLabel);
        context.add(Box.createHorizontalStrut(20));
        context.add(scoreLabel);

        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(BorderFactory.createEmptyBorder(0, 4, 4, 4));
        header.add(progressLabel, BorderLayout.WEST);
        header.add(context, BorderLayout.EAST);

        advanceButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));

        add(header, BorderLayout.NORTH);
        add(contentHost, BorderLayout.CENTER);
        add(advanceButton, BorderLayout.SOUTH);
    }

    public void showRound(
            RoundProgress.Snapshot progress,
            JComponent content,
            Runnable onAdvance) {
        showRound(
                progress,
                content,
                progress.isLastRound() ? "Finish" : "Next",
                onAdvance);
    }

    public void showRound(
            RoundProgress.Snapshot progress,
            JComponent content,
            String advanceText,
            Runnable onAdvance) {
        Objects.requireNonNull(progress, "Round progress is required");
        Objects.requireNonNull(content, "Round content is required");
        if (progress.complete()) {
            throw new IllegalArgumentException(
                    "Cannot display a completed round as active");
        }

        progressLabel.setText(
                "Round " + progress.currentRound() + " of " + progress.totalRounds());
        replaceContent(content);
        replaceAdvanceAction(onAdvance);
        advanceButton.setText(
                advanceText == null || advanceText.isBlank() ? "Next" : advanceText);
        advanceButton.setEnabled(false);
        advanceButton.setVisible(true);
    }

    public void setAdvanceEnabled(boolean enabled) {
        advanceButton.setEnabled(enabled);
    }

    public boolean isAdvanceEnabled() {
        return advanceButton.isEnabled();
    }

    /**
     * Optional context slots kept out of game-specific content so player
     * registration and scoring can be connected without changing round layouts.
     */
    public void setPlayerName(String playerName) {
        setOptionalText(playerLabel, "Player: ", playerName);
    }

    public void setScoreText(String score) {
        setOptionalText(scoreLabel, "Score: ", score);
    }

    public void showCompletion(String message) {
        progressLabel.setText("Complete");
        JLabel completion = new JLabel(
                message == null || message.isBlank() ? "Completed" : message,
                SwingConstants.CENTER);
        completion.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        replaceContent(completion);
        replaceAdvanceAction(null);
        advanceButton.setEnabled(false);
        advanceButton.setVisible(false);
    }

    private void replaceContent(JComponent content) {
        contentHost.removeAll();
        contentHost.add(content, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    private void replaceAdvanceAction(Runnable action) {
        for (ActionListener listener : advanceButton.getActionListeners()) {
            advanceButton.removeActionListener(listener);
        }
        if (action != null) {
            advanceButton.addActionListener(event -> action.run());
        }
    }

    private static void setOptionalText(
            JLabel label, String prefix, String value) {
        boolean present = value != null && !value.isBlank();
        label.setText(present ? prefix + value : "");
        label.setVisible(present);
    }
}
