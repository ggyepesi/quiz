package quiz.ui;

import aux.BrowserLauncher;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link quiz.Link}-annotated field as a clickable hyperlink:
 * left-click opens the URL in the browser, right-click copies it.
 *
 * Search/sort work off the model (no wiring needed here); the inner label
 * carries the field client properties so search highlighting still applies.
 */
public class QuizableLinkRow extends JPanel {

    private static final Color LINK_COLOR = new Color(0, 80, 200);

    private final String url;

    public QuizableLinkRow(
            String fieldName,
            List<String> fieldPath,
            String url) {

        super(new FlowLayout(FlowLayout.LEFT, 6, 2));
        setOpaque(false);

        this.url = url == null ? "" : url;

        String name = fieldName == null ? "" : fieldName;
        List<String> path = fieldPath == null
                ? List.of()
                : new ArrayList<>(fieldPath);

        if (!name.isBlank()) {
            JLabel nameLabel = new JLabel(name + ":");
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD));
            add(nameLabel);
        }

        JLabel link = new JLabel(asHtmlLink(this.url));
        link.setForeground(LINK_COLOR);
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        link.setToolTipText("Click to open · right-click to copy");

        // Field metadata so the search panel can highlight this row.
        link.putClientProperty(
                QuizableSearchPanel.FIELD_NAME_PROPERTY, name);
        link.putClientProperty(
                QuizableSearchPanel.FIELD_PATH_PROPERTY, path);
        link.putClientProperty(
                QuizableSearchPanel.FIELD_VALUE_PROPERTY, this.url);

        link.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showPopup(link, e.getPoint(), name, path);
                } else if (SwingUtilities.isLeftMouseButton(e)) {
                    BrowserLauncher.open(QuizableLinkRow.this.url);
                }
            }
        });

        add(link);
    }

    private void showPopup(
            Component invoker,
            Point p,
            String name,
            List<String> path) {

        JPopupMenu menu = new JPopupMenu();

        JMenuItem copyUrl = new JMenuItem("Copy URL");
        copyUrl.addActionListener(e -> copy(url));
        menu.add(copyUrl);

        if (!name.isBlank()) {
            JMenuItem copyField = new JMenuItem("Copy field name");
            copyField.addActionListener(e -> copy(name));

            JMenuItem copyPath = new JMenuItem("Copy field path");
            copyPath.addActionListener(e -> copy(String.join(".", path)));

            menu.addSeparator();
            menu.add(copyField);
            menu.add(copyPath);
        }

        menu.show(invoker, p.x, p.y);
    }

    private static String asHtmlLink(String url) {
        return "<html><u>" + escapeHtml(url) + "</u></html>";
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static void copy(String text) {
        Toolkit.getDefaultToolkit()
               .getSystemClipboard()
               .setContents(
                       new StringSelection(text == null ? "" : text),
                       null);
    }
}
