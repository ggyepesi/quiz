package quiz.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

public class QuizableTextRow extends JComponent implements QuizableTextSelectable {
    private static final int PAD_X = 6;
    private static final int PAD_Y = 4;
    private static final int GAP = 8;
    private static final int MAX_PREF_WIDTH = 420;

    private static final Color SEARCH_HIGHLIGHT =
            new Color(255, 245, 120);
    private static final Color SELECTION_BACKGROUND =
            new Color(80, 140, 255);
    private static final Color SELECTION_FOREGROUND =
            Color.WHITE;

    private record PaintLine(String text,
                             int x,
                             int baseline,
                             int top,
                             int bottom,
                             int lineIndex) {
    }

    private record TextPosition(int lineIndex, int offset) {
    }

    private final String fieldName;
    private final List<String> fieldPath;
    private final List<String> lines;
    private List<String> highlightTokens = List.of();

    private final QuizableTextSelection selection =
            new QuizableTextSelection();

    private int cachedWidth = -1;
    private List<PaintLine> cachedPaintLines = new ArrayList<>();

    public QuizableTextRow(String fieldName,
                           List<String> fieldPath,
                           Object rawValue) {
        this(fieldName, fieldPath, rawValue == null
                ? List.of()
                : List.of(String.valueOf(rawValue)));
    }

    public QuizableTextRow(String fieldName,
                           List<String> fieldPath,
                           List<String> lines) {
        QuizablePanel.RenderStats.textRows++;
        this.fieldName = fieldName == null ? "" : fieldName;
        this.fieldPath = fieldPath == null
                ? List.of()
                : new ArrayList<>(fieldPath);
        this.lines = lines == null ? List.of() : new ArrayList<>(lines);

        setOpaque(false);
        setFocusable(true);

        putClientProperty(QuizableSearchPanel.FIELD_NAME_PROPERTY, fieldName);
        putClientProperty(QuizableSearchPanel.FIELD_PATH_PROPERTY, this.fieldPath);
        putClientProperty(QuizableSearchPanel.FIELD_VALUE_PROPERTY,
                          String.join(" ", this.lines));

        addMouseListener(QuizableTextCopyMouseHandler.INSTANCE);
        addMouseMotionListener(QuizableTextCopyMouseHandler.INSTANCE);
        setToolTipText("Drag to select, right-click to copy");
    }

    public void beginSelection(Point p) {
        QuizableTextSelectionManager.activate(this);

        requestFocusInWindow();
        TextPosition pos = positionAt(p, computePaintLines(getWidth()));
        selection.setAnchor(pos.lineIndex(), pos.offset());
        repaint();
    }

    public void updateSelection(Point p) {
        if (!selection.hasAnchor()) {
            beginSelection(p);
            return;
        }

        TextPosition pos = positionAt(p, computePaintLines(getWidth()));
        selection.setFocus(pos.lineIndex(), pos.offset());
        repaint();
    }

    public void endSelection(Point p) {
        updateSelection(p);
    }

    public void showCopyPopup(Point p) {
        JPopupMenu menu = new JPopupMenu();
        QuizableTextSelectionManager.activate(this);

        String selectedText = selectedText();

        if (!selectedText.isBlank()) {
            JMenuItem copySelection = new JMenuItem("Copy selection");
            copySelection.addActionListener(e -> copyToClipboard(selectedText));
            menu.add(copySelection);
            menu.addSeparator();
        }

        JMenuItem copyRow = new JMenuItem("Copy row");
        copyRow.addActionListener(e -> copyToClipboard(fullText()));

        JMenuItem copyValue = new JMenuItem("Copy value");
        copyValue.addActionListener(e -> copyToClipboard(valueText()));

        menu.add(copyRow);
        menu.add(copyValue);

        if (!fieldName.isBlank()) {
            JMenuItem copyField = new JMenuItem("Copy field name");
            copyField.addActionListener(e -> copyToClipboard(fieldName));

            JMenuItem copyPath = new JMenuItem("Copy field path");
            copyPath.addActionListener(e -> copyToClipboard(pathText()));

            menu.addSeparator();
            menu.add(copyField);
            menu.add(copyPath);
        }

        menu.show(this, p.x, p.y);
    }

    private String selectedText() {
        if (selection.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        for (PaintLine line : computePaintLines(getWidth())) {
            if (!selection.intersectsLine(line.lineIndex())) {
                continue;
            }

            int start = clamp(selection.selectedStartForLine(line.lineIndex()),
                              0, line.text().length());
            int end = clamp(selection.selectedEndForLine(line.lineIndex(),
                                                         line.text().length()),
                            0, line.text().length());

            if (end < start) {
                int t = start;
                start = end;
                end = t;
            }

            if (sb.length() > 0) {
                sb.append('\n');
            }

            sb.append(line.text(), start, end);
        }

        return sb.toString();
    }

    private String fullText() {
        String value = valueText();
        return fieldName.isBlank() ? value : fieldName + ": " + value;
    }

    private String valueText() {
        return String.join("\n", lines);
    }

    private String pathText() {
        return String.join(".", fieldPath);
    }

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit()
               .getSystemClipboard()
               .setContents(new StringSelection(text == null ? "" : text),
                            null);
    }

    private List<String> wrappedLines(FontMetrics fm, int valueWidth) {
        List<String> out = new ArrayList<>();

        for (String line : lines) {
            wrapOneLine(line, fm, valueWidth, out);
        }

        return out.isEmpty() ? List.of("") : out;
    }

    private void wrapOneLine(String line,
                             FontMetrics fm,
                             int maxWidth,
                             List<String> out) {
        if (line == null || line.isBlank()) {
            out.add("");
            return;
        }

        String[] words = line.split("\\s+");
        StringBuilder current = new StringBuilder();

        for (String word : words) {
            String next = current.isEmpty() ? word : current + " " + word;

            if (fm.stringWidth(next) <= maxWidth) {
                current.setLength(0);
                current.append(next);
            } else {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                }
                current.setLength(0);
                current.append(word);
            }
        }

        if (!current.isEmpty()) {
            out.add(current.toString());
        }
    }

    public void setHighlightTokens(List<String> tokens) {
        highlightTokens = tokens == null ? List.of() : new ArrayList<>(tokens);
        repaint();
    }

    public void clearHighlight() {
        highlightTokens = List.of();
        repaint();
    }

    private Font fieldFont() {
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        return base.deriveFont(Font.BOLD);
    }

    private Font valueFont() {
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        return base;
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fmField = getFontMetrics(fieldFont());
        FontMetrics fmValue = getFontMetrics(valueFont());

        String prefix = fieldName.isEmpty() ? "" : fieldName + ":";
        int prefixWidth = fmField.stringWidth(prefix);

        int longest = 0;
        for (String line : lines) {
            longest = Math.max(longest, fmValue.stringWidth(line));
        }

        int naturalWidth = PAD_X
                + prefixWidth
                + (prefix.isEmpty() ? 0 : GAP)
                + longest
                + PAD_X;

        int width = Math.clamp(naturalWidth, 160, MAX_PREF_WIDTH);
        int valueWidth = Math.max(80, width - PAD_X - prefixWidth - GAP - PAD_X);

        List<String> wrapped = wrappedLines(fmValue, valueWidth);
        int height = PAD_Y * 2 + wrapped.size() * fmValue.getHeight();

        return new Dimension(width, height);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(80, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        Graphics2D g2 = (Graphics2D) g.create();

        try {
            Font fieldFont = fieldFont();
            Font valueFont = valueFont();

            FontMetrics fmField = g2.getFontMetrics(fieldFont);
            FontMetrics fmValue = g2.getFontMetrics(valueFont);

            String prefix = fieldName.isEmpty() ? "" : fieldName + ":";

            int x = PAD_X;
            int valueX = x;

            if (!prefix.isEmpty()) {
                g2.setFont(fieldFont);
                g2.setColor(getForeground());

                int prefixBaseline = PAD_Y + fmValue.getAscent();
                g2.drawString(prefix, x, prefixBaseline);

                valueX += fmField.stringWidth(prefix) + GAP;
            }

            g2.setFont(valueFont);

            for (PaintLine line : computePaintLines(getWidth())) {
                paintTextLine(g2, line.text(), line.x(), line.baseline(),
                              fmValue, line.lineIndex());
            }
        } finally {
            g2.dispose();
        }
    }

    private List<PaintLine> computePaintLines(int width) {
        if (width == cachedWidth && !cachedPaintLines.isEmpty()) {
            return cachedPaintLines;
        }

        FontMetrics fmField = getFontMetrics(fieldFont());
        FontMetrics fmValue = getFontMetrics(valueFont());

        String prefix = fieldName.isEmpty() ? "" : fieldName + ":";
        int prefixWidth = fmField.stringWidth(prefix);
        int valueX = PAD_X + (prefix.isEmpty() ? 0 : prefixWidth + GAP);
        int valueWidth = Math.max(80, width - valueX - PAD_X);

        List<String> wrapped = wrappedLines(fmValue, valueWidth);
        List<PaintLine> out = new ArrayList<>();

        int y = PAD_Y + fmValue.getAscent();

        for (int i = 0; i < wrapped.size(); i++) {
            String line = wrapped.get(i);
            out.add(new PaintLine(line, valueX, y,
                                  y - fmValue.getAscent(),
                                  y + fmValue.getDescent(),
                                  i));
            y += fmValue.getHeight();
        }

        cachedWidth = width;
        cachedPaintLines = out;
        return out;
    }

    private void paintTextLine(Graphics2D g2,
                               String text,
                               int x,
                               int baseline,
                               FontMetrics fm,
                               int lineIndex) {
        if (text == null) {
            return;
        }

        boolean[] searchMark = searchMarks(text);

        int selectedStart = selection.selectedStartForLine(lineIndex);
        int selectedEnd = selection.selectedEndForLine(lineIndex, text.length());

        selectedStart = selectedStart < 0 ? -1 : clamp(selectedStart, 0, text.length());
        selectedEnd = selectedEnd < 0 ? -1 : clamp(selectedEnd, 0, text.length());

        int pos = 0;

        while (pos < text.length()) {
            int start = pos;
            boolean highlighted = searchMark[pos];
            boolean selected = selectedStart >= 0
                    && selectedEnd >= 0
                    && pos >= selectedStart
                    && pos < selectedEnd;

            while (pos < text.length()
                    && searchMark[pos] == highlighted
                    && ((selectedStart >= 0
                         && selectedEnd >= 0
                         && pos >= selectedStart
                         && pos < selectedEnd) == selected)) {
                pos++;
            }

            String part = text.substring(start, pos);
            int partX = x + fm.stringWidth(text.substring(0, start));
            int partW = fm.stringWidth(part);

            if (selected) {
                g2.setColor(SELECTION_BACKGROUND);
                g2.fillRect(partX, baseline - fm.getAscent(), partW, fm.getHeight());
                g2.setColor(SELECTION_FOREGROUND);
            } else {
                if (highlighted) {
                    g2.setColor(SEARCH_HIGHLIGHT);
                    g2.fillRect(partX, baseline - fm.getAscent(), partW, fm.getHeight());
                }
                g2.setColor(getForeground());
            }

            g2.drawString(part, partX, baseline);
        }
    }

    private boolean[] searchMarks(String text) {
        boolean[] mark = new boolean[text.length()];
        String lower = text.toLowerCase();

        for (String token : highlightTokens) {
            if (token == null || token.isBlank()) {
                continue;
            }

            String tok = token.toLowerCase();
            int idx = 0;

            while ((idx = lower.indexOf(tok, idx)) >= 0) {
                for (int i = idx; i < idx + tok.length() && i < mark.length; i++) {
                    mark[i] = true;
                }

                idx += Math.max(1, tok.length());
            }
        }

        return mark;
    }

    private TextPosition positionAt(Point p, List<PaintLine> paintLines) {
        if (paintLines.isEmpty()) {
            return new TextPosition(0, 0);
        }

        PaintLine best = paintLines.get(0);

        for (PaintLine line : paintLines) {
            if (p.y >= line.top() && p.y <= line.bottom()) {
                best = line;
                break;
            }

            if (Math.abs(p.y - line.baseline())
                    < Math.abs(p.y - best.baseline())) {
                best = line;
            }
        }

        return new TextPosition(best.lineIndex(),
                                offsetForX(best.text(), p.x, best.x()));
    }

    private int offsetForX(String text, int mouseX, int textX) {
        FontMetrics fm = getFontMetrics(valueFont());

        if (mouseX <= textX) {
            return 0;
        }

        for (int i = 0; i < text.length(); i++) {
            int mid = textX
                    + fm.stringWidth(text.substring(0, i))
                    + fm.charWidth(text.charAt(i)) / 2;

            if (mouseX < mid) {
                return i;
            }
        }

        return text.length();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }


    @Override
    public void clearSelectionFromManager() {
        selection.clear();
        repaint();
    }
}
