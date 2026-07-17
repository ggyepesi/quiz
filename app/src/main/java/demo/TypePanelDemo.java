package demo;

import quiz.QuizableAdapter;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Self-contained demo of the type-panel / reference-chip rendering model.
 *
 * Demonstrates:
 *   1. Per-class type panels (Constellations, Stars)
 *   2. Entity fields rendered as clickable reference chips, not embedded cards
 *   3. Cross-panel navigation — clicking a chip scrolls to and highlights
 *      the target card in its own type panel
 *   4. Pre-registration — the render context knows all objects before
 *      any card is built, so single-object rendering never recurses
 *
 * Run TypePanelDemo.main() — no Wikidata connection required.
 */
public class TypePanelDemo extends JFrame {

    // ------------------------------------------------------------------
    // Domain model
    // ------------------------------------------------------------------

    /** Hard-wired Quizable constellation. */
    static class Constellation extends QuizableAdapter {
        public String name = "";
        public String abbreviation = "";
        public List<Constellation> neighbours = new ArrayList<>();
        public List<Star> stars = new ArrayList<>();

        Constellation(String name, String abbreviation) {
            this.name = name;
            this.abbreviation = abbreviation;
        }

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
        @Override public QuizableAdapter createNew() { return new Constellation("", ""); }
        @Override public String toString() { return name; }
    }

    /** Hard-wired Quizable star. */
    static class Star extends QuizableAdapter {
        public String name = "";
        public double magnitude = 0.0;
        public Constellation constellation;

        Star(String name, double magnitude) {
            this.name = name;
            this.magnitude = magnitude;
        }

        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
        @Override public QuizableAdapter createNew() { return new Star("", 0); }
        @Override public String toString() { return name; }
    }

    // ------------------------------------------------------------------
    // Render context
    // ------------------------------------------------------------------

    /**
     * Shared identity registry.
     * All domain objects are pre-registered here before any card is built.
     * When a card renderer encounters a reference to an already-registered
     * object, it produces a chip instead of recursing.
     */
    static class RenderContext {
        // object → the card panel that represents it (null = registered but not yet built)
        private final IdentityHashMap<Object, ObjectCard> registry =
                new IdentityHashMap<>();

        // listeners that want to know when an object is selected
        private final List<Consumer<Object>> selectionListeners = new ArrayList<>();

        void preRegister(Object obj) {
            registry.putIfAbsent(obj, null);
        }

        void register(Object obj, ObjectCard card) {
            registry.put(obj, card);
        }

        boolean isRegistered(Object obj) {
            return registry.containsKey(obj);
        }

        ObjectCard cardFor(Object obj) {
            return registry.get(obj);
        }

        void onSelected(Consumer<Object> listener) {
            selectionListeners.add(listener);
        }

        void select(Object obj) {
            selectionListeners.forEach(l -> l.accept(obj));
        }
    }

    // ------------------------------------------------------------------
    // Card — full rendering of one object
    // ------------------------------------------------------------------

    static class ObjectCard extends JPanel {
        private final Object obj;
        private static final Color NORMAL_BG     = new Color(250, 250, 255);
        private static final Color HIGHLIGHT_BG  = new Color(255, 240, 150);

        ObjectCard(Object obj, RenderContext ctx) {
            this.obj = obj;
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(NORMAL_BG);
            setOpaque(true);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(180, 180, 200), 1),
                    BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            setAlignmentX(LEFT_ALIGNMENT);

            // Register before building fields — prevents recursion
            ctx.register(obj, this);

            buildFields(obj, ctx);
        }

        void highlight(boolean on) {
            setBackground(on ? HIGHLIGHT_BG : NORMAL_BG);
            repaint();
        }

        Object domainObject() { return obj; }

        private void buildFields(Object obj, RenderContext ctx) {
            // Title row
            String title = obj instanceof QuizableAdapter qa
                    ? qa.getName() : obj.toString();
            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
            titleLabel.setAlignmentX(LEFT_ALIGNMENT);
            add(titleLabel);
            add(Box.createVerticalStrut(4));

            // Fields via reflection
            for (var field : obj.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                String fname = field.getName();
                Object value;
                try { value = field.get(obj); } catch (Exception e) { continue; }

                if (value == null) continue;
                if (fname.equals("name")) continue; // shown as title already

                add(buildFieldRow(fname, value, ctx));
                add(Box.createVerticalStrut(2));
            }
        }

        private JComponent buildFieldRow(
                String fieldName, Object value, RenderContext ctx) {

            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            row.setOpaque(false);
            row.setAlignmentX(LEFT_ALIGNMENT);

            JLabel label = new JLabel(fieldName + ":");
            label.setForeground(new Color(100, 100, 120));
            label.setFont(label.getFont().deriveFont(Font.ITALIC, 11f));
            row.add(label);

            if (value instanceof List<?> list) {
                if (list.isEmpty()) {
                    row.add(new JLabel("—"));
                } else {
                    for (Object item : list) {
                        row.add(buildValueChip(item, ctx));
                    }
                }
            } else {
                row.add(buildValueChip(value, ctx));
            }

            return row;
        }

        private JComponent buildValueChip(Object value, RenderContext ctx) {
            // If this object is already registered → reference chip
            if (ctx.isRegistered(value)) {
                return referenceChip(value, ctx);
            }

            // Scalar — just a label
            if (value instanceof Number) {
                return new JLabel(String.format("%.2f", ((Number) value).doubleValue()));
            }
            return new JLabel(value.toString());
        }

        private JComponent referenceChip(Object target, RenderContext ctx) {
            String label = target instanceof QuizableAdapter qa
                    ? qa.getName() : target.toString();

            JButton chip = new JButton(label);
            chip.setFont(chip.getFont().deriveFont(11f));
            chip.setMargin(new Insets(1, 6, 1, 6));
            chip.setFocusPainted(false);
            chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            chip.setBackground(new Color(220, 230, 255));
            chip.setToolTipText("Click to navigate to " + label);

            chip.addActionListener(e -> ctx.select(target));
            return chip;
        }
    }

    // ------------------------------------------------------------------
    // Type panel — one per domain class
    // ------------------------------------------------------------------

    static class TypePanel extends JPanel {
        private final String typeName;
        private final List<ObjectCard> cards = new ArrayList<>();
        private final JPanel cardsPanel = new JPanel();
        private final JScrollPane scroll;

        TypePanel(String typeName) {
            super(new BorderLayout(4, 4));
            this.typeName = typeName;

            cardsPanel.setLayout(new BoxLayout(cardsPanel, BoxLayout.Y_AXIS));
            cardsPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

            scroll = new JScrollPane(cardsPanel);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            scroll.setBorder(null);

            setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createEtchedBorder(),
                    typeName,
                    TitledBorder.LEFT,
                    TitledBorder.TOP,
                    new Font(Font.SANS_SERIF, Font.BOLD, 13)));

            add(scroll, BorderLayout.CENTER);
        }

        void addCard(ObjectCard card) {
            cards.add(card);
            if (!cards.isEmpty() && cards.size() > 1) {
                cardsPanel.add(Box.createVerticalStrut(6));
            }
            cardsPanel.add(card);
            cardsPanel.revalidate();
        }

        /**
         * Scrolls to and highlights the card for the given object.
         * Returns true if found.
         */
        boolean navigateTo(Object obj) {
            for (ObjectCard card : cards) {
                boolean match = card.domainObject() == obj;
                card.highlight(match);
                if (match) {
                    scrollToCard(card);
                }
            }
            return cards.stream().anyMatch(c -> c.domainObject() == obj);
        }

        private void scrollToCard(ObjectCard card) {
            SwingUtilities.invokeLater(() -> {
                Rectangle bounds = card.getBounds();
                cardsPanel.scrollRectToVisible(bounds);
                scroll.getViewport().scrollRectToVisible(
                        SwingUtilities.convertRectangle(
                                cardsPanel, bounds, scroll.getViewport()));
            });
        }

        void clearHighlights() {
            cards.forEach(c -> c.highlight(false));
        }

        String typeName() { return typeName; }
    }

    // ------------------------------------------------------------------
    // Demo wiring
    // ------------------------------------------------------------------

    private final RenderContext ctx = new RenderContext();
    private final List<TypePanel> typePanels = new ArrayList<>();

    public TypePanelDemo() {
        super("Type Panel Demo — Constellations & Stars");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1200, 700);
        setLocationByPlatform(true);

        // Build domain objects
        var data = buildData();

        // Pre-register ALL objects before building any card
        // This ensures reference chips are produced immediately,
        // even for a single-object render.
        for (Object obj : data.values()) {
            ctx.preRegister(obj);
        }
        // Pre-register stars too
        for (Object obj : data.values()) {
            if (obj instanceof Constellation c) {
                c.stars.forEach(ctx::preRegister);
            }
        }

        // Build type panels
        TypePanel constellationPanel = new TypePanel("Constellations");
        TypePanel starPanel = new TypePanel("Stars");

        typePanels.add(constellationPanel);
        typePanels.add(starPanel);

        // Build cards — pre-registration means no recursion into neighbours
        for (Object obj : data.values()) {
            if (obj instanceof Constellation c) {
                constellationPanel.addCard(new ObjectCard(c, ctx));
            }
        }

        Set<Star> allStars = new LinkedHashSet<>();
        for (Object obj : data.values()) {
            if (obj instanceof Constellation c) allStars.addAll(c.stars);
        }
        for (Star star : allStars) {
            starPanel.addCard(new ObjectCard(star, ctx));
        }

        // Cross-panel navigation: clicking a chip selects the target
        ctx.onSelected(target -> {
            typePanels.forEach(TypePanel::clearHighlights);
            typePanels.forEach(p -> p.navigateTo(target));
        });

        // Layout
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                                          titled("Constellations", scrolled(constellationPanel)),
                                          titled("Stars", scrolled(starPanel)));
        split.setResizeWeight(0.6);

        JLabel hint = new JLabel(
                "  Click any chip to navigate to that object in its type panel");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC, 12f));
        hint.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        add(hint, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    // ------------------------------------------------------------------
    // Hard-wired constellation data
    // ------------------------------------------------------------------

    private Map<String, Object> buildData() {
        // Constellations
        Constellation orion    = new Constellation("Orion",    "Ori");
        Constellation taurus   = new Constellation("Taurus",   "Tau");
        Constellation gemini   = new Constellation("Gemini",   "Gem");
        Constellation lepus    = new Constellation("Lepus",    "Lep");
        Constellation eridanus = new Constellation("Eridanus", "Eri");
        Constellation monoceros= new Constellation("Monoceros","Mon");

        // Neighbour relations (symmetric)
        link(orion,    taurus, gemini, lepus, eridanus, monoceros);
        link(taurus,   orion, gemini, eridanus);
        link(gemini,   orion, taurus, monoceros);
        link(lepus,    orion, eridanus);
        link(eridanus, orion, taurus, lepus);
        link(monoceros,orion, gemini);

        // Stars
        Star rigel      = star("Rigel",      0.13, orion);
        Star betelgeuse = star("Betelgeuse", 0.42, orion);
        Star bellatrix  = star("Bellatrix",  1.64, orion);
        Star aldebaran  = star("Aldebaran",  0.85, taurus);
        Star pollux     = star("Pollux",     1.14, gemini);
        Star castor     = star("Castor",     1.58, gemini);

        // Attach stars
        orion.stars.addAll(List.of(rigel, betelgeuse, bellatrix));
        taurus.stars.add(aldebaran);
        gemini.stars.addAll(List.of(pollux, castor));

        Map<String, Object> all = new LinkedHashMap<>();
        for (Constellation c : List.of(orion, taurus, gemini, lepus, eridanus, monoceros))
            all.put(c.name, c);
        return all;
    }

    private static void link(Constellation a, Constellation... neighbours) {
        a.neighbours.addAll(List.of(neighbours));
    }

    private static Star star(String name, double mag, Constellation c) {
        Star s = new Star(name, mag);
        s.constellation = c;
        return s;
    }

    // ------------------------------------------------------------------
    // Layout helpers
    // ------------------------------------------------------------------

    private static JComponent titled(String title, JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder());
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    private static JScrollPane scrolled(JComponent c) {
        JScrollPane s = new JScrollPane(c);
        s.getVerticalScrollBar().setUnitIncrement(16);
        return s;
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TypePanelDemo().setVisible(true));
    }
}