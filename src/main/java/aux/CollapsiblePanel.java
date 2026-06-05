package aux;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

public class CollapsiblePanel extends JPanel {

    private JPanel content;
    private JLabel header;
    private boolean expanded = false;

    public CollapsiblePanel(String title) {

        setLayout(new BorderLayout());

        header = new JLabel("▶ " + title);
        header.setBorder(BorderFactory.createEmptyBorder(4,4,4,4));
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        content = new JPanel();
        content.setVisible(false);

        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);

        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                toggle();
            }
        });
    }

    public JPanel getContentPanel() {
        return content;
    }

    private void toggle() {

        expanded = !expanded;

        content.setVisible(expanded);

        if (expanded)
            header.setText(header.getText().replace("▶", "▼"));
        else
            header.setText(header.getText().replace("▼", "▶"));

        revalidate();
    }
}