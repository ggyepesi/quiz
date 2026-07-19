package wikidata.explore.compiled;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.Selection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A project's Selections survive compilation and are resolvable on the compiled
 *  model, so the reify (compiled path) can reference them. */
class CompiledSelectionTest {

    @Test void selectionsCarryThroughCompile() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel src = new GeneratedClassModel("OscarNominations");
        src.instanceMapping().propertyPid("P1411");
        src.instanceMapping().additionalTypeQids().add("Q102427");
        project.addClass(src);

        Selection cats = new Selection("OscarCategories", Selection.Kind.VOCABULARY);
        cats.valueQids(List.of("Q102427", "Q106301"));
        project.addSelection(cats);

        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);

        assertEquals(1, compiled.selections().size());
        Selection found = compiled.findSelection("oscarcategories").orElseThrow();
        assertEquals(Selection.Kind.VOCABULARY, found.kind());
        assertTrue(found.valueQids().containsAll(List.of("Q102427", "Q106301")));
    }

    @Test void noSelectionsIsEmptyNotNull() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel src = new GeneratedClassModel("OscarNominations");
        src.instanceMapping().propertyPid("P1411");
        src.instanceMapping().additionalTypeQids().add("Q102427");
        project.addClass(src);

        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);
        assertTrue(compiled.selections().isEmpty());
        assertTrue(compiled.findSelection("nope").isEmpty());
    }
}
