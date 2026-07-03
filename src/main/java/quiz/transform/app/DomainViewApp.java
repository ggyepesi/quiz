package quiz.transform.app;

import quiz.QuizableGroup;
import quiz.facet.Facet;
import quiz.transform.ClassTransformPlan;
import quiz.transform.View;
import quiz.ui.QuizablePanelView;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

/**
 * Standalone view-layer app over a SAVED domain snapshot (no model builder, no
 * re-fetch): load the instances, apply a {@link View} (filter + project + group),
 * render the result. The view runs on {@code quiz.transform} directly over the
 * {@link WikidataDynamicObject} snapshot (FieldAccess is DynamicFields-aware).
 * Example: Oscar winners per category per year.
 */
public final class DomainViewApp {

    private DomainViewApp() {}

    public static void main(String[] args) throws Exception {
        File snapshot = new File(args.length > 0 ? args[0]
                : "data/wikidata/oscarnominations/oscarnominations.snapshot.json");

        List<WikidataDynamicObject> pool =
                new WikidataDynamicObjectJsonStore().loadAll(snapshot);

        // A view over the domain instances: keep the winners, project the fields
        // we group/show, then group per category, then per year.
        View winnersView = new View("Oscar winners", WikidataDynamicObject.class)
                .plan(ClassTransformPlan.keeping(WikidataDynamicObject.class)
                        .whereFieldEquals("won", Boolean.TRUE))
                .groupBy(Facet.reference("category"), Facet.field("year"));

        QuizableGroup root = winnersView.render(pool);

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
