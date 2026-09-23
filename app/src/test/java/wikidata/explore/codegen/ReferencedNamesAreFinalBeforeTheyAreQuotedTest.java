package wikidata.explore.codegen;

import datasource.schema.FieldType;
import objectview.Viewable;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A composed display name quotes the names of the objects its class references, so the
 * quoted name has to be final before it is read.
 *
 * <p>Materialization populates objects iteratively — a domain graph can be deeper than
 * the JVM stack — and populating a parent is what discovers its children, so the work
 * queue fills parents-first. Naming in that same order made a parent quote the label its
 * child arrived with rather than the name the child ends up with: on Historical Positions
 * the raw Wikidata label instead of the canonical one. The recursion this replaced got
 * the order right by unwinding child-first, which is easy to lose and impossible to see
 * without a reference whose own name is composed.
 */
class ReferencedNamesAreFinalBeforeTheyAreQuotedTest {

    @Test void aNameComposedFromAReferenceQuotesThatReferencesCanonicalName()
            throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel child = new GeneratedClassModel("Child");
        child.addField("realName", FieldType.STRING, FieldCardinality.SINGLE);
        child.canonical(new CanonicalSpec()
                .displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField("realName"));
        GeneratedClassModel parent = new GeneratedClassModel("Parent");
        parent.addField("child", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Child");
        parent.canonical(new CanonicalSpec()
                .displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField("child"));
        project.rootClass(parent);
        project.addClass(child);

        WikidataDynamicObject childObject = new WikidataDynamicObject("Q2", "raw label");
        childObject.type("Child");
        childObject.put("realName", "Canonical Child");
        WikidataDynamicObject parentObject = new WikidataDynamicObject("Q1", "raw parent");
        parentObject.type("Parent");
        parentObject.put("child", childObject);

        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(project)) {
            List<Viewable> mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(List.of(parentObject, childObject));

            assertEquals("Canonical Child", named(mapped, "Child"));
            assertEquals("Canonical Child", named(mapped, "Parent"),
                    "the parent quotes the name the child ends up with, not the label it "
                            + "arrived with");
        }
    }

    private static String named(List<Viewable> mapped, String type) {
        return mapped.stream()
                .filter(value -> value.getClass().getSimpleName().equals(type))
                .findFirst().orElseThrow().getDisplayName();
    }
}
