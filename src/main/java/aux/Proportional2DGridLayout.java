package aux;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class Proportional2DGridLayout implements LayoutManager2 {

    private final int columns;
    private final int hgap;
    private final int vgap;

    public Proportional2DGridLayout(int columns, int hgap, int vgap) {
        if (columns <= 0) {
            throw new IllegalArgumentException("columns must be positive");
        }

        this.columns = columns;
        this.hgap = hgap;
        this.vgap = vgap;
    }

    @Override
    public void layoutContainer(Container parent) {
        synchronized (parent.getTreeLock()) {
            List<Component> comps = visibleComponents(parent);
            if (comps.isEmpty()) {
                return;
            }

            Insets in = parent.getInsets();

            int rows = (int) Math.ceil(comps.size() / (double) columns);

            int availableWidth = parent.getWidth() - in.left - in.right - hgap * Math.max(0, columns - 1);
            int availableHeight = parent.getHeight() - in.top - in.bottom - vgap * Math.max(0, rows - 1);

            availableWidth = Math.max(0, availableWidth);
            availableHeight = Math.max(0, availableHeight);

            double[] columnWeights = new double[columns];
            double[] rowWeights = new double[rows];

            for (int i = 0; i < comps.size(); i++) {
                Component c = comps.get(i);
                Dimension d = c.getPreferredSize();

                int row = i / columns;
                int col = i % columns;

                columnWeights[col] = Math.max(columnWeights[col], Math.max(1, d.width));
                rowWeights[row] = Math.max(rowWeights[row], Math.max(1, d.height));
            }

            int[] columnWidths = distribute(availableWidth, columnWeights);
            int[] rowHeights = distribute(availableHeight, rowWeights);

            int[] xs = new int[columns];
            int[] ys = new int[rows];

            xs[0] = in.left;
            for (int c = 1; c < columns; c++) {
                xs[c] = xs[c - 1] + columnWidths[c - 1] + hgap;
            }

            ys[0] = in.top;
            for (int r = 1; r < rows; r++) {
                ys[r] = ys[r - 1] + rowHeights[r - 1] + vgap;
            }

            for (int i = 0; i < comps.size(); i++) {
                Component c = comps.get(i);

                int row = i / columns;
                int col = i % columns;

                c.setBounds(
                        xs[col],
                        ys[row],
                        columnWidths[col],
                        rowHeights[row]);
            }
        }
    }

    private int[] distribute(int total, double[] weights) {
        int[] sizes = new int[weights.length];

        double sum = 0.0;
        for (double w : weights) {
            sum += Math.max(1.0, w);
        }

        int used = 0;

        for (int i = 0; i < weights.length; i++) {
            if (i == weights.length - 1) {
                sizes[i] = total - used;
            } else {
                sizes[i] = (int) Math.round(total * (Math.max(1.0, weights[i]) / sum));
                used += sizes[i];
            }
        }

        return sizes;
    }

    private List<Component> visibleComponents(Container parent) {
        List<Component> out = new ArrayList<>();

        for (Component c : parent.getComponents()) {
            if (c.isVisible()) {
                out.add(c);
            }
        }

        return out;
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        synchronized (parent.getTreeLock()) {
            List<Component> comps = visibleComponents(parent);
            Insets in = parent.getInsets();

            if (comps.isEmpty()) {
                return new Dimension(in.left + in.right, in.top + in.bottom);
            }

            int rows = (int) Math.ceil(comps.size() / (double) columns);

            int[] colWidths = new int[columns];
            int[] rowHeights = new int[rows];

            for (int i = 0; i < comps.size(); i++) {
                Dimension d = comps.get(i).getPreferredSize();

                int row = i / columns;
                int col = i % columns;

                colWidths[col] = Math.max(colWidths[col], d.width);
                rowHeights[row] = Math.max(rowHeights[row], d.height);
            }

            int width = in.left + in.right + hgap * Math.max(0, columns - 1);
            for (int w : colWidths) {
                width += w;
            }

            int height = in.top + in.bottom + vgap * Math.max(0, rows - 1);
            for (int h : rowHeights) {
                height += h;
            }

            return new Dimension(width, height);
        }
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return preferredLayoutSize(parent);
    }

    @Override
    public Dimension maximumLayoutSize(Container target) {
        return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override public void addLayoutComponent(Component comp, Object constraints) {}
    @Override public void addLayoutComponent(String name, Component comp) {}
    @Override public void removeLayoutComponent(Component comp) {}
    @Override public float getLayoutAlignmentX(Container target) { return 0.5f; }
    @Override public float getLayoutAlignmentY(Container target) { return 0.5f; }
    @Override public void invalidateLayout(Container target) {}
}