package quiz.web.sources;

import domain.datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import domain.runtime.WikidataDynamicObject;
import domain.runtime.WikidataDynamicObjectJsonStore;
import domain.config.ClassKind;
import domain.config.FieldCardinality;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.GeneratedProjectModelStore;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A graph constraint's instances are annotations — one record per candidate saying what a
 * classification run decided — and the web served them beside the entities they are about.
 *
 * <p>They are in the project's own snapshot because that is where they belong, and a
 * snapshot's member bit was decided before a graph constraint was a kind of class, so it
 * marks them like any other root. Historical Positions therefore published 1,307
 * "Accepted/Review/Rejected" rows as quiz content. The rule was already written
 * everywhere else it applies: the annotation set's own dataset row is saved
 * {@code served(false)}, and materialization keeps graph classes out of the ordinary
 * instances list. Registration was the last place that had not been told.
 */
class AGraphConstraintIsNotQuizContentTest {

    @Test void aGraphClassIsNotServedWhileTheClassItAnnotatesIs(@TempDir Path dir)
            throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel office = new GeneratedClassModel("Office");
        office.addField("label", FieldType.STRING, FieldCardinality.SINGLE);
        project.rootClass(office);
        GeneratedClassModel validity = new GeneratedClassModel("OfficeValidity");
        validity.classKind(ClassKind.GRAPH);
        validity.addField("Graph decision", FieldType.STRING, FieldCardinality.SINGLE);
        validity.graphSource(new domain.config.GraphClassSource(
                new domain.datasource.graph.GraphDiscoveryConfiguration.StartNode(
                        "Office", "",
                        domain.datasource.graph.GraphDiscoveryConfiguration.NodeUse
                                .INTERMEDIATE_ONLY),
                List.of(new domain.datasource.graph.GraphDiscoveryConfiguration.NextNode(
                        new domain.datasource.graph.GraphRelation("wikidata", "P31"),
                        domain.datasource.graph.GraphTraversalDirection.OUTGOING,
                        domain.datasource.graph.GraphDiscoveryConfiguration.NodeUse
                                .CLASS_POPULATION,
                        "Office", null))));
        project.addClass(validity);

        File model = dir.resolve("offices.model.json").toFile();
        new GeneratedProjectModelStore().save(project, model);
        File snapshot = dir.resolve("offices.snapshot.json").toFile();
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                List.of(object("Q1", "Office", "label", "Pharaoh"),
                        object("Q1-decision", "OfficeValidity",
                                "Graph decision", "Accepted")),
                snapshot, project);

        quiz.web.ViewableStore store = new quiz.web.ViewableStore();
        GeneratedSource.registerAll(store, "Office", snapshot, model);

        assertTrue(store.types().contains("Office"),
                "the class the run was about is quiz content");
        assertFalse(store.types().contains("OfficeValidity"),
                "a graph constraint's annotations record a classification run; they are "
                        + "read in its result tab, never served as a browsable dataset");
    }

    /**
     * Only the kind decides. A model may well traverse from the very class it serves, so
     * being named as a graph's start node says nothing about whether a class is content.
     */
    @Test void anOrdinaryClassStaysServedWhateverAGraphDoesWithIt(@TempDir Path dir)
            throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel office = new GeneratedClassModel("Office");
        office.addField("label", FieldType.STRING, FieldCardinality.SINGLE);
        project.rootClass(office);

        File model = dir.resolve("offices.model.json").toFile();
        new GeneratedProjectModelStore().save(project, model);
        File snapshot = dir.resolve("offices.snapshot.json").toFile();
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(
                List.of(object("Q1", "Office", "label", "Pharaoh")), snapshot, project);

        quiz.web.ViewableStore store = new quiz.web.ViewableStore();
        GeneratedSource.registerAll(store, "Office", snapshot, model);

        assertTrue(store.types().contains("Office"));
    }

    private static WikidataDynamicObject object(
            String id, String type, String field, String value) {
        WikidataDynamicObject object = new WikidataDynamicObject(id, id);
        object.type(type);
        object.typeKey(type);
        object.put(field, value);
        return object;
    }
}
