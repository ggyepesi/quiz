package quiz.ocr;

import objectview.utils.swing.CachedImage;
import aux.RememberingFileChooser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class LogoMaskBlurEditor extends JFrame {

    private enum Mode {
        RECTANGLE,
        POLYGON
    }

    private final ImagePanel originalPanel = new ImagePanel();
    private final ImagePanel blurredPanel = new ImagePanel();

    private final List<RelativeRect> rectangles = new ArrayList<>();
    private final List<RelativePolygon> polygons = new ArrayList<>();

    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final RememberingFileChooser openImageChooser =
            new RememberingFileChooser(this, "logoMask.openImage");

    private final RememberingFileChooser loadMaskChooser =
            new RememberingFileChooser(this, "logoMask.loadMask");

    private final RememberingFileChooser savePngChooser =
            new RememberingFileChooser(this, "logoMask.savePng");

    private final RememberingFileChooser saveMaskChooser =
            new RememberingFileChooser(this, "logoMask.saveMask");

    private File selectedFile;
    private BufferedImage originalImage;
    private BufferedImage blurredImage;

    private Mode mode = Mode.RECTANGLE;

    private final JCheckBox blurComplementCheck =
            new JCheckBox("Blur outside (complement)");

    // Dataset type the mask is keyed under: data/masks/{type}/{imageName}.json.
    private final JTextField typeField = new JTextField("SportTeam", 10);

    public LogoMaskBlurEditor() {
        super("Logo Mask Blur Editor");

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1500, 850);

        JButton openButton = new JButton("Open Image");
        JButton previewButton = new JButton("Preview Blur");
        JButton saveImageButton = new JButton("Save Blurred PNG");
        JButton saveMaskButton = new JButton("Save Mask JSON");
        JButton loadMaskButton = new JButton("Load Mask JSON");
        JButton clearButton = new JButton("Clear Mask");
        JButton finishPolyButton = new JButton("Finish Poly");
        JButton undoButton = new JButton("Undo");

        JToggleButton showMaskButton = new JToggleButton("Show Mask", true);

        JRadioButton rectModeButton = new JRadioButton("Rect Mode", true);
        JRadioButton polyModeButton = new JRadioButton("Poly Mode");

        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(rectModeButton);
        modeGroup.add(polyModeButton);

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row1.add(openButton);
        row1.add(previewButton);
        row1.add(saveImageButton);
        row1.add(saveMaskButton);
        row1.add(loadMaskButton);
        row1.add(clearButton);
        row1.add(new JLabel("Type:"));
        row1.add(typeField);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row2.add(showMaskButton);
        row2.add(rectModeButton);
        row2.add(polyModeButton);
        row2.add(finishPolyButton);
        row2.add(undoButton);
        row2.add(blurComplementCheck);

        // Two rows so nothing wraps off the (single-row-height) NORTH region.
        JPanel top = new JPanel(new GridLayout(2, 1));
        top.add(row1);
        top.add(row2);

        JScrollPane originalScroll = new JScrollPane(originalPanel);
        JScrollPane blurredScroll = new JScrollPane(blurredPanel);

        originalScroll.setMinimumSize(new Dimension(400, 400));
        blurredScroll.setMinimumSize(new Dimension(400, 400));

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                originalScroll,
                blurredScroll
        );
        split.setResizeWeight(0.5);

        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        originalPanel.setEditable(true);
        originalPanel.setData(rectangles, polygons);

        blurredPanel.setEditable(false);
        blurredPanel.setData(rectangles, polygons);

        openButton.addActionListener(e -> openImage());
        previewButton.addActionListener(e -> previewBlur());
        saveImageButton.addActionListener(e -> saveBlurredImage());
        saveMaskButton.addActionListener(e -> saveMask());
        loadMaskButton.addActionListener(e -> loadMask());
        clearButton.addActionListener(e -> clearMask());
        finishPolyButton.addActionListener(e -> originalPanel.finishCurrentPolygon());
        undoButton.addActionListener(e -> originalPanel.undoLast());

        showMaskButton.addActionListener(e -> {
            boolean show = showMaskButton.isSelected();
            originalPanel.setShowMask(show);
            blurredPanel.setShowMask(show);
        });

        rectModeButton.addActionListener(e -> {
            mode = Mode.RECTANGLE;
            originalPanel.setMode(mode);
        });

        polyModeButton.addActionListener(e -> {
            mode = Mode.POLYGON;
            originalPanel.setMode(mode);
        });

        // Re-render immediately so the inside/outside choice is visible.
        blurComplementCheck.addActionListener(e -> previewBlur());

        // Changing the type re-loads the matching mask for the open image.
        typeField.addActionListener(e -> autoLoadMask());

        originalPanel.setMode(mode);
        setupShortcuts();
    }

    private void setupShortcuts() {
        getRootPane().registerKeyboardAction(
                e -> saveMask(),
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_S,
                        InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_IN_FOCUSED_WINDOW
                                            );

        getRootPane().registerKeyboardAction(
                e -> previewBlur(),
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_B,
                        InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_IN_FOCUSED_WINDOW
                                            );

        getRootPane().registerKeyboardAction(
                e -> {
                    originalPanel.cancelCurrentPolygon();
                    originalPanel.repaint();
                },
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
                                            );

        // Enter finishes the in-progress polygon cleanly (no stray vertex).
        getRootPane().registerKeyboardAction(
                e -> originalPanel.finishCurrentPolygon(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
                                            );

        // Ctrl+Z undoes the last vertex / last shape.
        getRootPane().registerKeyboardAction(
                e -> originalPanel.undoLast(),
                KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK),
                JComponent.WHEN_IN_FOCUSED_WINDOW
                                            );
    }

    private void openImage() {
        File file = openImageChooser.chooseOpenFile();

        if (file == null) {
            return;
        }

        try {
            selectedFile = file;

            CachedImage cached = new CachedImage(
                    selectedFile.getName(),
                    selectedFile.toURI().toString(),
                    selectedFile.getName().toLowerCase().endsWith(".svg")
            );

            originalImage = toBufferedImage(cached.getFullImage());
            blurredImage = null;

            rectangles.clear();
            polygons.clear();

            originalPanel.setImage(originalImage);
            blurredPanel.setImage(null);
            blurredPanel.setFallbackSize(originalImage.getWidth(), originalImage.getHeight());

            // Auto-load a previously-saved mask for this image, if any.
            autoLoadMask();

            repaint();

        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void previewBlur() {
        if (originalImage == null) {
            return;
        }

        try {
            blurredImage = deepCopy(originalImage);

            int w = blurredImage.getWidth();
            int h = blurredImage.getHeight();

            // Combine all shapes into one mask, then blur either the mask
            // itself or its complement (everything outside the shapes).
            java.awt.geom.Area mask = new java.awt.geom.Area();
            for (RelativeRect rr : rectangles) {
                mask.add(new java.awt.geom.Area(clamp(rr.toPixels(w, h), w, h)));
            }
            for (RelativePolygon rp : polygons) {
                mask.add(new java.awt.geom.Area(rp.toPixels(w, h)));
            }

            if (!mask.isEmpty()) {
                Shape region = mask;
                if (blurComplementCheck.isSelected()) {
                    java.awt.geom.Area complement =
                            new java.awt.geom.Area(new Rectangle(0, 0, w, h));
                    complement.subtract(mask);
                    region = complement;
                }
                blurShape(blurredImage, region, 12);
            }

            blurredPanel.setImage(blurredImage);

        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void saveBlurredImage() {
        if (blurredImage == null) {
            previewBlur();
        }

        if (blurredImage == null) {
            return;
        }

        String suggested = selectedFile == null
                ? "blurred.png"
                : stripExtension(selectedFile.getName()) + "-blurred.png";

        File out = savePngChooser.chooseSaveFile(suggested);

        if (out == null) {
            return;
        }

        try {
            File parent = out.getParentFile();

            if (parent != null) {
                parent.mkdirs();
            }

            ImageIO.write(blurredImage, "png", out);

        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void saveMask() {
        if (rectangles.isEmpty() && polygons.isEmpty()) {
            JOptionPane.showMessageDialog(
                    this,
                    "Draw at least one rectangle or polygon before saving.",
                    "Nothing to save",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // Default: the keyed path data/masks/{type}/{imageName}.json, so the
        // quizzes/web pick it up automatically. Falls back to a chooser if no
        // image is open.
        File out = maskPathFor();
        if (out == null) {
            out = saveMaskChooser.chooseSaveFile("mask.json");
        }
        if (out == null) {
            return;
        }

        try {
            if (out.getParentFile() != null) {
                out.getParentFile().mkdirs();
            }
            MaskFile mf = new MaskFile();
            mf.image = selectedFile == null ? null : selectedFile.getName();
            mf.rectangles = new ArrayList<>(rectangles);
            mf.polygons = new ArrayList<>(polygons);
            mf.blurComplement = blurComplementCheck.isSelected();

            mapper.writeValue(out, mf);
            setTitle("Logo Mask Blur Editor — saved " + out.getPath());
            JOptionPane.showMessageDialog(
                    this,
                    "Saved mask to:\n" + out.getAbsolutePath(),
                    "Mask saved",
                    JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception ex) {
            showError(ex);
        }
    }

    // The canonical mask file for the open image, keyed like the resolver:
    // data/masks/{type}/{imageNameWithoutExtension}.json. Null if no image.
    private File maskPathFor() {
        if (selectedFile == null) {
            return null;
        }
        String type = typeField.getText().trim();
        if (type.isEmpty()) {
            type = "Misc";
        }
        return quiz.ocr.QuizImageBlurrer.maskFile(type, stripExtension(selectedFile.getName()));
    }

    private void autoLoadMask() {
        File mf = maskPathFor();
        if (mf != null && mf.isFile()) {
            loadMaskFile(mf);
        }
    }

    private void loadMask() {
        File file = loadMaskChooser.chooseOpenFile();
        if (file != null) {
            loadMaskFile(file);
        }
    }

    private void loadMaskFile(File file) {
        try {
            MaskFile mf = mapper.readValue(file, MaskFile.class);

            rectangles.clear();
            polygons.clear();

            if (mf.rectangles != null) {
                rectangles.addAll(mf.rectangles);
            }

            if (mf.regions != null) {
                rectangles.addAll(mf.regions);
            }

            if (mf.polygons != null) {
                polygons.addAll(mf.polygons);
            }

            blurComplementCheck.setSelected(mf.blurComplement);

            originalPanel.repaint();
            blurredPanel.repaint();
            previewBlur();

        } catch (Exception ex) {
            showError(ex);
        }
    }

    private void clearMask() {
        rectangles.clear();
        polygons.clear();
        blurredImage = null;

        originalPanel.cancelCurrentPolygon();
        originalPanel.repaint();
        blurredPanel.setImage(null);

        if (originalImage != null) {
            blurredPanel.setFallbackSize(
                    originalImage.getWidth(),
                    originalImage.getHeight()
                                        );
        }
    }

    public static class MaskFile {
        public String image;

        public List<RelativeRect> rectangles = new ArrayList<>();

        // Backward-compatible old name.
        public List<RelativeRect> regions = new ArrayList<>();

        public List<RelativePolygon> polygons = new ArrayList<>();

        // When true, blur everything outside the shapes rather than inside.
        public boolean blurComplement;
    }

    public static class RelativeRect {
        public double x;
        public double y;
        public double width;
        public double height;

        public RelativeRect() {
        }

        public RelativeRect(
                double x,
                double y,
                double width,
                double height
                           ) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        Rectangle toPixels(int imageWidth, int imageHeight) {
            return new Rectangle(
                    (int) Math.round(x * imageWidth),
                    (int) Math.round(y * imageHeight),
                    (int) Math.round(width * imageWidth),
                    (int) Math.round(height * imageHeight)
            );
        }

        static RelativeRect fromPixels(
                Rectangle r,
                int imageWidth,
                int imageHeight
                                      ) {
            return new RelativeRect(
                    r.x / (double) imageWidth,
                    r.y / (double) imageHeight,
                    r.width / (double) imageWidth,
                    r.height / (double) imageHeight
            );
        }
    }

    public static class RelativePoint {
        public double x;
        public double y;

        public RelativePoint() {
        }

        public RelativePoint(double x, double y) {
            this.x = x;
            this.y = y;
        }

        Point toPixels(int imageWidth, int imageHeight) {
            return new Point(
                    (int) Math.round(x * imageWidth),
                    (int) Math.round(y * imageHeight)
            );
        }

        static RelativePoint fromPixels(
                Point p,
                int imageWidth,
                int imageHeight
                                       ) {
            return new RelativePoint(
                    p.x / (double) imageWidth,
                    p.y / (double) imageHeight
            );
        }
    }

    public static class RelativePolygon {
        public List<RelativePoint> points = new ArrayList<>();

        public RelativePolygon() {
        }

        public RelativePolygon(List<RelativePoint> points) {
            this.points = points;
        }

        Polygon toPixels(int imageWidth, int imageHeight) {
            Polygon polygon = new Polygon();

            if (points == null) {
                return polygon;
            }

            for (RelativePoint p : points) {
                Point px = p.toPixels(imageWidth, imageHeight);
                polygon.addPoint(px.x, px.y);
            }

            return polygon;
        }

        static RelativePolygon fromPixels(
                List<Point> points,
                int imageWidth,
                int imageHeight
                                         ) {
            List<RelativePoint> rel = new ArrayList<>();

            for (Point p : points) {
                rel.add(RelativePoint.fromPixels(
                        p,
                        imageWidth,
                        imageHeight
                                                ));
            }

            return new RelativePolygon(rel);
        }
    }

    private class ImagePanel extends JPanel implements Scrollable {

        private static final int EMPTY_WIDTH = 600;
        private static final int EMPTY_HEIGHT = 500;

        private BufferedImage image;
        private int fallbackWidth = EMPTY_WIDTH;
        private int fallbackHeight = EMPTY_HEIGHT;

        private List<RelativeRect> rectangles;
        private List<RelativePolygon> polygons;

        private boolean editable;
        private boolean showMask = true;
        private Mode mode = Mode.RECTANGLE;

        private Point dragStart;
        private Point dragEnd;

        private Point mousePoint;

        private final List<Point> currentPolygon = new ArrayList<>();

        // Click within this many panel pixels of the first vertex to close.
        private static final int CLOSE_RADIUS = 10;

        ImagePanel() {
            setBackground(Color.DARK_GRAY);
            setOpaque(true);

            MouseAdapter mouse = new MouseAdapter() {

                @Override
                public void mousePressed(MouseEvent e) {
                    if (!editable || image == null) {
                        return;
                    }

                    requestFocusInWindow();

                    if (SwingUtilities.isRightMouseButton(e)) {
                        System.out.println("Right");
                        // While building a polygon, right-click finishes it
                        // (needs >= 3 vertices); otherwise it deletes a shape.
                        if (mode == Mode.POLYGON && currentPolygon.size() >= 3) {
                            System.out.println("Finish");
                            finishCurrentPolygon();
                        } else {
                            System.out.println("Delete");
                            deleteAt(e.getPoint());
                        }
                        return;
                    }

                    if (mode == Mode.POLYGON) {
                        // Every left press adds a vertex — no click-count and no
                        // click-to-close, both of which made placing points near
                        // each other / near the start unreliable. Finish with
                        // right-click or Enter; Esc cancels.
                        if (SwingUtilities.isLeftMouseButton(e)) {
                            System.out.println("Left");
                            addPolygonPoint(e.getPoint());
                        }
                        return;
                    }

                    // RECTANGLE mode: double-click deletes a shape under it.
                    if (e.getClickCount() == 2
                            && SwingUtilities.isLeftMouseButton(e)) {
                        System.out.println("Double and left: delete");
                        deleteAt(e.getPoint());
                        return;
                    }

                    dragStart = e.getPoint();
                    dragEnd = e.getPoint();
                    repaint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    mousePoint = e.getPoint();

                    // Keep the rubber-band line tracking the cursor even if the
                    // mouse is dragged (button held) while building a polygon.
                    if (mode == Mode.POLYGON) {
                        System.out.println("Poly dragged");
                        if (!currentPolygon.isEmpty()) {
                            repaint();
                        }
                        return;
                    }

                    if (!editable
                            || image == null
                            || mode != Mode.RECTANGLE
                            || dragStart == null) {
                        return;
                    }

                    dragEnd = e.getPoint();
                    repaint();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    mousePoint = e.getPoint();
                    if (mode == Mode.POLYGON
                            && !currentPolygon.isEmpty()) {
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (!editable
                            || image == null
                            || mode != Mode.RECTANGLE
                            || dragStart == null) {
                        return;
                    }

                    dragEnd = e.getPoint();

                    Rectangle panelRect =
                            normalizeRect(dragStart, dragEnd);

                    if (panelRect.width >= 4 && panelRect.height >= 4) {
                        Rectangle imageRect = panelToImageRect(panelRect);

                        if (imageRect.width > 0 && imageRect.height > 0) {
                            rectangles.add(RelativeRect.fromPixels(
                                    imageRect,
                                    image.getWidth(),
                                    image.getHeight()
                                                                  ));
                        }
                    }

                    dragStart = null;
                    dragEnd = null;
                    repaint();
                }
            };

            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        void setEditable(boolean editable) {
            this.editable = editable;
        }

        void setData(
                List<RelativeRect> rectangles,
                List<RelativePolygon> polygons
                    ) {
            this.rectangles = rectangles;
            this.polygons = polygons;
        }

        void setShowMask(boolean showMask) {
            this.showMask = showMask;
            repaint();
        }

        void setMode(Mode mode) {
            this.mode = mode;
            cancelCurrentPolygon();
            repaint();
        }

        void cancelCurrentPolygon() {
            currentPolygon.clear();
            dragStart = null;
            dragEnd = null;
            repaint();
        }

        // Undo: drop the last in-progress vertex if drawing a polygon,
        // otherwise remove the most recently finished shape.
        void undoLast() {
            if (!currentPolygon.isEmpty()) {
                currentPolygon.remove(currentPolygon.size() - 1);
            } else if (polygons != null && !polygons.isEmpty()) {
                polygons.remove(polygons.size() - 1);
            } else if (rectangles != null && !rectangles.isEmpty()) {
                rectangles.remove(rectangles.size() - 1);
            }
            repaint();
        }

        void setFallbackSize(int width, int height) {
            this.fallbackWidth = Math.max(EMPTY_WIDTH, width);
            this.fallbackHeight = Math.max(EMPTY_HEIGHT, height);
            revalidate();
            repaint();
        }

        void setImage(BufferedImage image) {
            this.image = image;

            if (image != null) {
                fallbackWidth = image.getWidth();
                fallbackHeight = image.getHeight();
            }

            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            if (image != null) {
                return new Dimension(image.getWidth(), image.getHeight());
            }

            return new Dimension(fallbackWidth, fallbackHeight);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(
                Rectangle visibleRect,
                int orientation,
                int direction
                                             ) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(
                Rectangle visibleRect,
                int orientation,
                int direction
                                              ) {
            return 120;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            Container parent = getParent();

            if (parent instanceof JViewport viewport) {
                return viewport.getWidth() > getPreferredSize().width;
            }

            return false;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            Container parent = getParent();

            if (parent instanceof JViewport viewport) {
                return viewport.getHeight() > getPreferredSize().height;
            }

            return false;
        }

        private void addPolygonPoint(Point panelPoint) {
            // Clamp into the drawn-image rectangle: panelToImagePoint() returns
            // null for clicks on the letterbox margin, which was silently
            // dropping vertices. A click anywhere in the panel now maps to the
            // nearest valid image point.
            Point imagePoint = panelToImagePoint(clampToImageBounds(panelPoint));

            if (imagePoint == null) {
                return; // only when there is no image
            }

            currentPolygon.add(imagePoint);
            repaint();
        }

        private Point clampToImageBounds(Point p) {
            Rectangle b = imageBounds();
            if (b.width <= 0 || b.height <= 0) {
                return p;
            }
            int x = Math.max(b.x, Math.min(b.x + b.width - 1, p.x));
            int y = Math.max(b.y, Math.min(b.y + b.height - 1, p.y));
            return new Point(x, y);
        }

        private void finishCurrentPolygon() {
            if (currentPolygon.size() < 3) {
                return;
            }

            polygons.add(RelativePolygon.fromPixels(
                    currentPolygon,
                    image.getWidth(),
                    image.getHeight()
                                                   ));

            currentPolygon.clear();
            repaint();
        }

        private void deleteAt(Point panelPoint) {
            Point imgPoint = panelToImagePoint(panelPoint);

            if (imgPoint == null) {
                return;
            }

            for (int i = polygons.size() - 1; i >= 0; i--) {
                Polygon p = polygons.get(i).toPixels(
                        image.getWidth(),
                        image.getHeight()
                                                    );

                if (p.contains(imgPoint)) {
                    polygons.remove(i);
                    repaint();
                    return;
                }
            }

            for (int i = rectangles.size() - 1; i >= 0; i--) {
                Rectangle r = rectangles.get(i).toPixels(
                        image.getWidth(),
                        image.getHeight()
                                                        );

                if (r.contains(imgPoint)) {
                    rectangles.remove(i);
                    repaint();
                    return;
                }
            }
        }

        private Point panelToImagePoint(Point panelPoint) {
            if (image == null) {
                return null;
            }

            Rectangle bounds = imageBounds();

            if (!bounds.contains(panelPoint)) {
                return null;
            }

            double sx = image.getWidth() / (double) bounds.width;
            double sy = image.getHeight() / (double) bounds.height;

            int x = (int) Math.round((panelPoint.x - bounds.x) * sx);
            int y = (int) Math.round((panelPoint.y - bounds.y) * sy);

            x = Math.max(0, Math.min(image.getWidth() - 1, x));
            y = Math.max(0, Math.min(image.getHeight() - 1, y));

            return new Point(x, y);
        }

        private Rectangle panelToImageRect(Rectangle panelRect) {
            if (image == null) {
                return new Rectangle();
            }

            Rectangle bounds = imageBounds();

            int x1 = Math.max(panelRect.x, bounds.x);
            int y1 = Math.max(panelRect.y, bounds.y);
            int x2 = Math.min(
                    panelRect.x + panelRect.width,
                    bounds.x + bounds.width
                             );
            int y2 = Math.min(
                    panelRect.y + panelRect.height,
                    bounds.y + bounds.height
                             );

            if (x2 <= x1 || y2 <= y1) {
                return new Rectangle();
            }

            Point p1 = panelToImagePoint(new Point(x1, y1));
            Point p2 = panelToImagePoint(new Point(x2, y2));

            if (p1 == null || p2 == null) {
                return new Rectangle();
            }

            int ix1 = Math.min(p1.x, p2.x);
            int iy1 = Math.min(p1.y, p2.y);
            int ix2 = Math.max(p1.x, p2.x);
            int iy2 = Math.max(p1.y, p2.y);

            return new Rectangle(
                    ix1,
                    iy1,
                    Math.max(1, ix2 - ix1),
                    Math.max(1, iy2 - iy1)
            );
        }

        private Point imageToPanelPoint(Point imagePoint) {
            Rectangle bounds = imageBounds();

            double sx = bounds.width / (double) image.getWidth();
            double sy = bounds.height / (double) image.getHeight();

            return new Point(
                    bounds.x + (int) Math.round(imagePoint.x * sx),
                    bounds.y + (int) Math.round(imagePoint.y * sy)
            );
        }

        private Rectangle imageToPanelRect(Rectangle imageRect) {
            Rectangle bounds = imageBounds();

            double sx = bounds.width / (double) image.getWidth();
            double sy = bounds.height / (double) image.getHeight();

            return new Rectangle(
                    bounds.x + (int) Math.round(imageRect.x * sx),
                    bounds.y + (int) Math.round(imageRect.y * sy),
                    Math.max(1, (int) Math.round(imageRect.width * sx)),
                    Math.max(1, (int) Math.round(imageRect.height * sy))
            );
        }

        private Polygon imageToPanelPolygon(Polygon imagePolygon) {
            Polygon panelPolygon = new Polygon();

            for (int i = 0; i < imagePolygon.npoints; i++) {
                Point p = imageToPanelPoint(new Point(
                        imagePolygon.xpoints[i],
                        imagePolygon.ypoints[i]
                ));

                panelPolygon.addPoint(p.x, p.y);
            }

            return panelPolygon;
        }

        private Rectangle imageBounds() {
            if (image == null) {
                return new Rectangle();
            }

            int panelW = getWidth();
            int panelH = getHeight();

            // Fit the image to the panel — scale up small images too, so they
            // fill the editor and are easy to click (don't cap at 1.0).
            double scale = Math.min(
                    panelW / (double) image.getWidth(),
                    panelH / (double) image.getHeight()
                                   );

            int drawW = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int drawH = Math.max(1, (int) Math.round(image.getHeight() * scale));

            int x = (panelW - drawW) / 2;
            int y = (panelH - drawH) / 2;

            return new Rectangle(x, y, drawW, drawH);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);

            Graphics2D g = (Graphics2D) g0.create();

            try {
                if (image == null) {
                    g.setColor(Color.LIGHT_GRAY);
                    g.drawString("No image", 20, 30);
                    return;
                }

                Rectangle bounds = imageBounds();

                g.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR
                                  );

                g.drawImage(
                        image,
                        bounds.x,
                        bounds.y,
                        bounds.width,
                        bounds.height,
                        null
                           );

                if (showMask) {
                    paintSavedMasks(g);
                }

                paintCurrentGesture(g);

            } finally {
                g.dispose();
            }
        }

        private void paintSavedMasks(Graphics2D g) {
            g.setStroke(new BasicStroke(3f));
            g.setColor(new Color(255, 0, 0, 230));

            if (rectangles != null) {
                for (RelativeRect rr : rectangles) {
                    Rectangle r = imageToPanelRect(rr.toPixels(
                            image.getWidth(),
                            image.getHeight()
                                                              ));
                    g.drawRect(r.x, r.y, r.width, r.height);
                }
            }

            if (polygons != null) {
                for (RelativePolygon rp : polygons) {
                    Polygon p = imageToPanelPolygon(rp.toPixels(
                            image.getWidth(),
                            image.getHeight()
                                                               ));
                    g.drawPolygon(p);
                }
            }
        }

        private void paintCurrentGesture(Graphics2D g) {
            g.setColor(new Color(255, 255, 0, 240));
            g.setStroke(new BasicStroke(3f));

            if (mode == Mode.RECTANGLE
                    && dragStart != null
                    && dragEnd != null) {
                Rectangle r = normalizeRect(dragStart, dragEnd);
                g.drawRect(r.x, r.y, r.width, r.height);
            }

            if (mode == Mode.POLYGON
                    && !currentPolygon.isEmpty()) {

                int extra = mousePoint != null ? 1 : 0;
                int n = currentPolygon.size() + extra;
                int[] xs = new int[n];
                int[] ys = new int[n];

                int i = 0;
                for (Point imagePoint : currentPolygon) {
                    Point pp = imageToPanelPoint(imagePoint);
                    xs[i] = pp.x;
                    ys[i] = pp.y;
                    i++;
                }

                // Rubber-band segment to the cursor. Draw it straight to the
                // panel mouse point: the old code routed it through
                // panelToImagePoint(), which returns null when the cursor is
                // over the letterbox margin, making the line vanish off-image.
                if (mousePoint != null) {
                    xs[i] = mousePoint.x;
                    ys[i] = mousePoint.y;
                }

                g.drawPolyline(xs, ys, n);

                for (Point imagePoint : currentPolygon) {
                    Point p = imageToPanelPoint(imagePoint);
                    g.fillOval(p.x - 4, p.y - 4, 8, 8);
                }

                // Ring the first vertex once there are enough points to close:
                // a "ready to finish (right-click / Enter)" indicator.
                if (currentPolygon.size() >= 3) {
                    Point first = imageToPanelPoint(currentPolygon.get(0));
                    g.setColor(new Color(0, 200, 255, 240));
                    g.drawOval(
                            first.x - CLOSE_RADIUS,
                            first.y - CLOSE_RADIUS,
                            CLOSE_RADIUS * 2,
                            CLOSE_RADIUS * 2
                              );
                }
            }
        }

        private static Rectangle normalizeRect(Point a, Point b) {
            int x1 = Math.min(a.x, b.x);
            int y1 = Math.min(a.y, b.y);
            int x2 = Math.max(a.x, b.x);
            int y2 = Math.max(a.y, b.y);

            return new Rectangle(x1, y1, x2 - x1, y2 - y1);
        }
    }

    private static void blurShape(
            BufferedImage image,
            Shape shape,
            int radius
                                 ) {
        Rectangle bounds = clamp(
                shape.getBounds(),
                image.getWidth(),
                image.getHeight()
                                );

        if (bounds.width <= 0 || bounds.height <= 0) {
            return;
        }

        BufferedImage copy = deepCopy(image);

        for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
            for (int x = bounds.x; x < bounds.x + bounds.width; x++) {

                if (!shape.contains(x, y)) {
                    continue;
                }

                long a = 0;
                long r = 0;
                long g = 0;
                long b = 0;
                int count = 0;

                for (int yy = y - radius; yy <= y + radius; yy++) {
                    for (int xx = x - radius; xx <= x + radius; xx++) {
                        if (xx < 0 || yy < 0
                                || xx >= copy.getWidth()
                                || yy >= copy.getHeight()) {
                            continue;
                        }

                        int argb = copy.getRGB(xx, yy);

                        a += (argb >>> 24) & 0xff;
                        r += (argb >>> 16) & 0xff;
                        g += (argb >>> 8) & 0xff;
                        b += argb & 0xff;
                        count++;
                    }
                }

                int aa = (int) (a / count);
                int rr = (int) (r / count);
                int gg = (int) (g / count);
                int bb = (int) (b / count);

                image.setRGB(
                        x,
                        y,
                        (aa << 24) | (rr << 16) | (gg << 8) | bb
                            );
            }
        }
    }

    private static Rectangle clamp(Rectangle r, int maxW, int maxH) {
        int x1 = Math.max(0, r.x);
        int y1 = Math.max(0, r.y);
        int x2 = Math.min(maxW, r.x + r.width);
        int y2 = Math.min(maxH, r.y + r.height);

        return new Rectangle(
                x1,
                y1,
                Math.max(0, x2 - x1),
                Math.max(0, y2 - y1)
        );
    }

    private static BufferedImage deepCopy(BufferedImage src) {
        BufferedImage out = new BufferedImage(
                src.getWidth(),
                src.getHeight(),
                BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D g = out.createGraphics();

        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }

        return out;
    }

    private static BufferedImage toBufferedImage(Image image) {
        if (image instanceof BufferedImage b) {
            return b;
        }

        BufferedImage out = new BufferedImage(
                image.getWidth(null),
                image.getHeight(null),
                BufferedImage.TYPE_INT_ARGB
        );

        Graphics2D g = out.createGraphics();

        try {
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }

        return out;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }

    private void showError(Exception ex) {
        ex.printStackTrace();

        JOptionPane.showMessageDialog(
                this,
                ex.toString(),
                "Error",
                JOptionPane.ERROR_MESSAGE
                                     );
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(
                () -> new LogoMaskBlurEditor().setVisible(true)
                                  );
    }
}