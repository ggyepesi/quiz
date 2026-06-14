package quiz.ui;

import quiz.Quizable;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Hosts several {@link QuizablePanelView}s side by side that share one
 * {@link QuizableRenderContext}, so a reference field in one view (e.g. a
 * constellation's {@code stars}) renders as a chip that, on click, scrolls
 * to and flashes the target's card in its own view — and vice versa.
 *
 * Usage:
 * <pre>
 *   MultiQuizableView mv = new MultiQuizableView();
 *   mv.addSection("Constellations", Constellation.class, constellations);
 *   mv.addSection("Stars", Star.class, stars);
 *   mv.build(1);
 * </pre>
 *
 * All objects across all sections are pre-registered before any card is
 * built, which is what makes a reference resolve to a chip rather than an
 * embedded card.
 */
public class MultiQuizableView extends JPanel {

    private final QuizableRenderContext context = new QuizableRenderContext();
    private final List<Section> sections = new ArrayList<>();
    private boolean built;

    public MultiQuizableView() {
        super(new BorderLayout());
        context.setInPlaceNavigation(true);
    }

    /** The shared context, e.g. to pre-register extra objects. */
    public QuizableRenderContext context() {
        return context;
    }

    public void addSection(
            String title,
            Class<? extends Quizable> type,
            List<? extends Quizable> objects) {

        if (built) {
            throw new IllegalStateException("addSection() after build()");
        }
        sections.add(new Section(
                title,
                type,
                new ArrayList<>(objects)));
    }

    public void build(int columnsPerView) {
        if (built) {
            return;
        }
        built = true;

        // Pre-register every object so cross-references render as chips.
        for (Section s : sections) {
            for (Quizable q : s.objects()) {
                context.addTopLevel(q);
            }
        }

        List<JComponent> bodies = new ArrayList<>();
        for (Section s : sections) {
            bodies.add(buildSection(s, columnsPerView));
        }

        add(layout(bodies), BorderLayout.CENTER);
        revalidate();
    }

    private JComponent buildSection(Section s, int columns) {
        QuizablePanelView view = new QuizablePanelView();
        view.setRenderContext(context);

        for (Quizable q : s.objects()) {
            view.addQuizable(q);
        }
        view.createCardsPanel(Math.max(1, columns));

        JPanel body = new JPanel(new BorderLayout(4, 4));
        body.setBorder(BorderFactory.createTitledBorder(s.title()));

        if (!s.objects().isEmpty()) {
            QuizableSearchPanel search =
                    new QuizableSearchPanel(s.type());
            search.setTarget(view.getCardsPanel(), view.getCardsScrollPane());
            view.addTargetListener(search);
            body.add(search, BorderLayout.NORTH);
        }

        body.add(view.getCardsScrollPane(), BorderLayout.CENTER);
        return body;
    }

    /**
     * Lays the sections out side by side (split for two, grid for more).
     *
     * This is deliberate, not cosmetic: navigation relies on the target
     * card being visible. {@code focusTopLevel} scrolls to + flashes the
     * card and brings the window forward, but it does not reveal a hidden
     * container — so a tabbed layout would jump to a card on an inactive
     * tab without selecting that tab. Switching to tabs would require a
     * "reveal" hook (select the owning tab before scrolling) wired through
     * the shared context.
     */
    private JComponent layout(List<JComponent> bodies) {
        if (bodies.isEmpty()) {
            return new JPanel();
        }
        if (bodies.size() == 1) {
            return bodies.get(0);
        }
        if (bodies.size() == 2) {
            JSplitPane split = new JSplitPane(
                    JSplitPane.HORIZONTAL_SPLIT,
                    bodies.get(0),
                    bodies.get(1));
            split.setResizeWeight(0.5);
            split.setContinuousLayout(true);
            return split;
        }

        JPanel grid = new JPanel(new GridLayout(1, bodies.size(), 6, 6));
        bodies.forEach(grid::add);
        return grid;
    }

    private record Section(
            String title,
            Class<? extends Quizable> type,
            List<Quizable> objects) {
    }
}
