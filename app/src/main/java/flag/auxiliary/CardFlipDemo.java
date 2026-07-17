package flag.auxiliary;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class CardFlipDemo extends JFrame {

    public CardFlipDemo() {
        setTitle("Card Flip Animation");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(300, 400);
        setLocationRelativeTo(null);
        
        CardPanel cardPanel = new CardPanel();
        add(cardPanel);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new CardFlipDemo().setVisible(true);
        });
    }

    // Custom panel representing a card with flip animation
    class CardPanel extends JPanel {

        private CardLayout cardLayout;
        private JPanel frontPanel;
        private JPanel backPanel;
        private boolean isFrontShowing = true;

        public CardPanel() {
            cardLayout = new CardLayout();
            setLayout(cardLayout);

            // Front side of the card
            frontPanel = createFrontPanel();
            // Back side of the card
            backPanel = createBackPanel();

            add(frontPanel, "front");
            add(backPanel, "back");

            // Add mouse listener for flipping on click
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    flipCard();
                }
            });
        }

        private JPanel createFrontPanel() {
            JPanel panel = new JPanel();
            panel.setBackground(Color.WHITE);
            JLabel label = new JLabel("Front Side");
            label.setFont(new Font("Arial", Font.BOLD, 24));
            panel.add(label);
            return panel;
        }

        private JPanel createBackPanel() {
            JPanel panel = new JPanel();
            panel.setBackground(Color.LIGHT_GRAY);
            JLabel label = new JLabel("Back Side");
            label.setFont(new Font("Arial", Font.BOLD, 24));
            panel.add(label);
            return panel;
        }

        private void flipCard() {
            // Animate flip
            Timer timer = new Timer(100, null);
            final int frames = 6;
            final int[] currentFrame = {0};

            timer.addActionListener(e -> {
                float progress = (float) currentFrame[0] / frames;
                // For flip effect, scale width from 1 to 0 then 0 to 1
                float scale;

                if (progress <= 0.5f) {
                    scale = 1 - (progress * 2); // 1 to 0
                } else {
                    scale = (progress - 0.5f) * 2; // 0 to 1
                }

                // Apply scale transform
                applyScale(scale);

                if (currentFrame[0] == frames / 2) {
                    // Switch cards at halfway point
                    isFrontShowing = !isFrontShowing;
                    cardLayout.show(CardPanel.this, isFrontShowing ? "front" : "back");
                }

                currentFrame[0]++;
                if (currentFrame[0] > frames) {
                    ((Timer) e.getSource()).stop();
                    // Reset scale
                    applyScale(1);
                }
            });
            timer.start();
        }

        private void applyScale(float scale) {
            // Since Swing doesn't support scale directly, we simulate flip by resizing
            int width = getWidth();
            int height = getHeight();
            int newWidth = (int) (width * scale);
            // Prevent zero width for visibility
            newWidth = Math.max(newWidth, 1);
            setPreferredSize(new Dimension(newWidth, height));
            revalidate();
        }
    }
}

