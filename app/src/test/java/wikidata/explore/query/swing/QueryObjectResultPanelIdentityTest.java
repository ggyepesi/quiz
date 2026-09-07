package wikidata.explore.query.swing;

import org.junit.jupiter.api.Test;
import wikidata.explore.query.result.ObjectQueryResult;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryObjectResultPanelIdentityTest {
    @Test void multiTypeViewKeepsDatasourceIdentityOutOfTheTitle() throws Exception {
        var project = new wikidata.explore.model.GeneratedProjectModel();
        project.rootClass().className("Person");
        project.addClass(new wikidata.explore.model.GeneratedClassModel("Work"));
        var personSource = entity("Q1", "One", "Person");
        var workSource = entity("Q2", "Two", "Work");
        workSource.wikidataStatementSources(List.of(new quiz.source.WikidataStatementSource(
                "Q2$a", "Q2", "P166", "Q3", "Three")));
        try (var runtime = new wikidata.explore.codegen.GeneratedViewableRuntimeBuilder()
                .build(project)) {
            List<objectview.Viewable> instances =
                    new wikidata.explore.codegen.GeneratedViewableMapper(runtime)
                            .mapRoots(List.of(personSource, workSource));
            objectview.Viewable person = instances.getFirst();
            QueryObjectResultPanel panel = new QueryObjectResultPanel();
            ObjectQueryResult result = new ObjectQueryResult(instances, null, null);

            panel.accept(result);
            SwingUtilities.invokeAndWait(() -> { });

            assertNotNull(panel.activeRenderContext());
            assertNull(panel.activeRenderContext().cardDecoration(person));
            assertEquals(List.of("Person", "Work"),
                    new java.util.ArrayList<>(result.byType().keySet()),
                    "provenance is inspectable without becoming a result type");
            Object value = objectview.field.FieldSet.of(person)
                    .read("wikidataSource");
            assertEquals("Q1", ((quiz.source.WikidataSource)
                    assertInstanceOf(List.class, value).getFirst()).qid());

            List<objectview.view.SearchableView> sections = new ArrayList<>();
            collect(panel, objectview.view.SearchableView.class, sections);
            assertEquals(2, sections.size());
            assertTrue(sections.stream().allMatch(view ->
                            view.search().getViewConfig().hasField("wikidataSource")),
                    "the real multi-type instances view discovers the normal "
                            + "datasource-declared field from each generated class");
        }
    }

    private static wikidata.explore.extract.WikidataDynamicObject entity(
            String qid, String label, String type) {
        var entity = new wikidata.explore.extract.WikidataDynamicObject(qid, label);
        entity.type(type);
        return entity;
    }

    private static <T extends Component> void collect(
            Container root, Class<T> type, List<T> result) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) result.add(type.cast(child));
            if (child instanceof Container nested) collect(nested, type, result);
        }
    }
}
