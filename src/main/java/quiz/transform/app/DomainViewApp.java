package quiz.transform.app;

import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.facet.Facet;
import quiz.facet.FacetGrouper;
import quiz.ui.QuizablePanelView;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

/**
 * Standalone view-layer app over a SAVED domain snapshot (no model builder, no
 * re-fetch): load the instances, apply a view (filter + group), render the
 * result. First slice — a hard-coded "Oscar winners per category per year" view
 * working directly on the {@link WikidataDynamicObject} snapshot; the transform
 * plumbing ({@code quiz.transform}) plugs in next once FieldAccess is
 * DynamicFields-aware.
 */
public final class DomainViewApp {

    private DomainViewApp() {}

    public static void main(String[] args) throws Exception {
        File snapshot = new File(args.length > 0 ? args[0]
                : "data/wikidata/oscarnominations/oscarnominations.snapshot.json");

        List<WikidataDynamicObject> pool =
                new WikidataDynamicObjectJsonStore().loadAll(snapshot);

        // View: keep the winners (Nomination.won == true) …
        List<Quizable> winners = pool.stream()
                .filter(o -> o != null
                        && "Nomination".equals(o.typeName())
                        && Boolean.TRUE.equals(o.get("won")))
                .map(o -> (Quizable) o)
                .toList();

        // … then group per category, then per year.
        QuizableGroup root = FacetGrouper.groupNested(
                "Oscar winners  (" + winners.size() + ")",
                winners,
                List.of(Facet.reference("category"), Facet.field("year")));

        SwingUtilities.invokeLater(() -> show(root, snapshot.getName()));
    }

    private static void show(QuizableGroup root, String title) {
        QuizablePanelView view = new QuizablePanelView();
        view.addQuizable(root);
        view.createCardsPanel(1);

        JFrame frame = new JFrame("Domain View — " + title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(view.getCardsScrollPane(), BorderLayout.CENTER);
        frame.setSize(1000, 850);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
