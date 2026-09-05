package wikidata.explore.compiled;

import wikidata.explore.model.EntityBound;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.Selection;
import wikidata.explore.model.VocabularySelection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A project's Selections survive compilation and are resolvable on the compiled
 *  model, so the reify (compiled path) can reference them. */
class CompiledSelectionTest {

    @Test void selectionsCarryThroughCompile() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel src = new GeneratedClassModel("OscarNominations");
        src.membership(EntityBound.relation("P1411", List.of("Q102427"), false));
        project.addClass(src);

        VocabularySelection cats = new VocabularySelection("OscarCategories");
        cats.valueQids(List.of("Q102427", "Q106301"));
        project.addSelection(cats);

        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);

        assertEquals(1, compiled.selections().size());
        Selection found = compiled.findSelection("oscarcategories").orElseThrow();
        assertEquals(Selection.Kind.VOCABULARY, found.kind());
        assertTrue(((VocabularySelection) found).valueQids()
                .containsAll(List.of("Q102427", "Q106301")));
    }

    @Test void noSelectionsIsEmptyNotNull() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel src = new GeneratedClassModel("OscarNominations");
        src.membership(EntityBound.relation("P1411", List.of("Q102427"), false));
        project.addClass(src);

        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);
        assertTrue(compiled.selections().isEmpty());
        assertTrue(compiled.findSelection("nope").isEmpty());
    }
}
