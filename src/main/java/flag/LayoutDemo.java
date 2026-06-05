package flag;

import javax.swing.*;
import javax.swing.GroupLayout.Alignment;
import java.awt.Dimension;
import java.awt.GridLayout;

class GroupLayoutDemo {    
        public static void main(String[] args) {
            SwingUtilities.invokeLater(() -> {
                JFrame frame = new JFrame("Three TextAreas with GroupLayout");
                frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    
                // Create the JTextAreas
                JTextArea textArea1 = new JTextArea();
                JTextArea textArea2 = new JTextArea();
                JTextArea textArea3 = new JTextArea();
    
                // Set a fixed height for all JTextAreas
                /*
                int fixedHeight = 100; // pixels
                Dimension preferredSize = new Dimension(200, fixedHeight);
                textArea1.setPreferredSize(preferredSize);
                textArea2.setPreferredSize(preferredSize);
                textArea3.setPreferredSize(preferredSize);
    */
                // Wrap JTextAreas in JScrollPane for better usability
                JScrollPane scrollPane1 = new JScrollPane(textArea1);
                JScrollPane scrollPane2 = new JScrollPane(textArea2);
                JScrollPane scrollPane3 = new JScrollPane(textArea3);
    
                // Create a JPanel and set GroupLayout
                JPanel panel = new JPanel();
                GroupLayout layout = new GroupLayout(panel);
                panel.setLayout(layout);
    
                // Enable automatic gaps
                layout.setAutoCreateGaps(true);
                layout.setAutoCreateContainerGaps(true);
    
                // Horizontal group: create a parallel group for scroll panes with same size
                layout.setHorizontalGroup(
                    layout.createSequentialGroup()
                        .addComponent(scrollPane1, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                        .addComponent(scrollPane2, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                        .addComponent(scrollPane3, GroupLayout.PREFERRED_SIZE, GroupLayout.DEFAULT_SIZE, GroupLayout.PREFERRED_SIZE)
                );
    
                // Vertical group: align all scroll panes vertically, with fixed height
                layout.setVerticalGroup(
                    layout.createParallelGroup(Alignment.BASELINE)
                        .addComponent(scrollPane1)//, GroupLayout.PREFERRED_SIZE, fixedHeight, GroupLayout.PREFERRED_SIZE)
                        .addComponent(scrollPane2)//, GroupLayout.PREFERRED_SIZE, fixedHeight, GroupLayout.PREFERRED_SIZE)
                        .addComponent(scrollPane3)//, GroupLayout.PREFERRED_SIZE, fixedHeight, GroupLayout.PREFERRED_SIZE)
                );
    
                // Add panel to frame and display
                frame.add(panel);
                frame.pack();
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            });
        }
    }

public class LayoutDemo {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Three TextAreas");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

            // Create a JPanel with GridLayout: 1 row, 3 columns
            JPanel panel = new JPanel();
            panel.setLayout(new GridLayout(1, 3, 5, 5)); // optional: gaps

            // Heights for each JTextArea
            int height = 100; // example height in pixels

            // Create 3 JTextAreas with same width & specified height
            for (int i = 0; i < 3; i++) {
                JTextArea textArea = new JTextArea();

                // Set preferred size: width will be managed by layout
                textArea.setPreferredSize(new Dimension(200, height)); // width = 200

                // Optional: set line wrap, etc.
                textArea.setLineWrap(true);
                textArea.setWrapStyleWord(true);

                // Add to panel
                panel.add(textArea);
            }

            // Optional: add scroll pane around each JTextArea
            // for better usability, but here directly added to panel
            frame.add(panel);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}

    