package quiz.transform.app;

import quiz.QuizableGroup;
import quiz.transform.View;
import quiz.transform.ViewSpec;
import quiz.ui.QuizablePanelView;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.List;

/**
 * Standalone view-layer app over a SAVED domain snapshot (no model builder, no
 * re-fetch): load the instances, edit a {@link ViewSpec} on the left
 * ({@link ViewConfigPanel}), render the grouped result on the right. Views run on
 * {@code quiz.transform} directly over the {@link WikidataDynamicObject} snapshot.
 */
public final class DomainViewApp {

    private DomainViewApp() {}

    public static void main(String[] args) throws Exception {
        File snapshot = new File(args.length > 0 ? args[0]
                : "data/wikidata/oscarnominations/oscarnominations.snapshot.json");

        List<WikidataDynamicObject> pool =
                new WikidataDynamicObjectJsonStore().loadAll(snapshot);
        DomainSchema schema = new DomainSchema(pool);

        SwingUtilities.invokeLater(() -> show(snapshot.getName(), pool, schema));
    }

    private static void show(String title, List<WikidataDynamicObject> pool,
                             DomainSchema schema) {

        ViewConfigPanel config = new ViewConfigPanel(schema, defaultSpec(schema));

        JPanel renderHolder = new JPanel(new BorderLayout());
        config.setOnRender(() -> render(renderHolder, config.buildSpec(), pool));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, config, renderHolder);
        split.setResizeWeight(0.34);

        JFrame frame = new JFrame("Domain View — " + title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(split, BorderLayout.CENTER);
        frame.setSize(1300, 850);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        render(renderHolder, config.buildSpec(), pool);
    }

    private static void render(JPanel holder, ViewSpec spec,
                               List<WikidataDynamicObject> pool) {
        View view = spec.build(WikidataDynamicObject.class);
        QuizableGroup root = view.render(pool);

        QuizablePanelView v = new QuizablePanelView();
        v.addQuizable(root);
        v.createCardsPanel(1);

        holder.removeAll();
        holder.add(v.getCardsScrollPane(), BorderLayout.CENTER);
        holder.revalidate();
        holder.repaint();
    }

    /** A ready-to-run example if the snapshot has a Nomination.won field. */
    private static ViewSpec defaultSpec(DomainSchema schema) {
        ViewSpec spec = new ViewSpec();
        if (schema.types().contains("Nomination")
                && schema.fields("Nomination").contains("won")) {
            spec.name = "Oscar winners";
            spec.memberType = "Nomination";
            spec.filters.add(new ViewSpec.Filter("won", Boolean.TRUE));
            spec.grouping.add(new ViewSpec.GroupBy("category", "REFERENCE"));
            spec.grouping.add(new ViewSpec.GroupBy("year", "VALUE"));
        } else if (!schema.types().isEmpty()) {
            spec.memberType = schema.types().get(0);
        }
        return spec;
    }
}
