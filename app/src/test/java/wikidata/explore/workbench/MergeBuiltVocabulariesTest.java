package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.VocabularySelection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Folding generation-built vocabularies back into the live model: fill an empty
 *  descriptive vocab, create a missing one, never overwrite an authored one. */
class MergeBuiltVocabulariesTest {

    private static VocabularySelection vocab(String name, String... qids) {
        VocabularySelection v = new VocabularySelection(name);
        v.valueQids(List.of(qids));
        return v;
    }

    @Test void fillsEmptyCreatesMissingAndLeavesAuthoredUntouched() {
        // The copy generation ran on: three vocabs all built with values.
        GeneratedProjectModel built = new GeneratedProjectModel();
        built.addSelection(vocab("NomineeType", "Q5", "Q11424"));         // built descriptive
        built.addSelection(vocab("WorkGenre", "Q130232"));                // invented by the load
        built.addSelection(vocab("OscarCategories", "Q1", "Q2", "Q3"));   // authored elsewhere

        // The live model: NomineeType present-but-empty, WorkGenre absent,
        // OscarCategories authored with its own values.
        GeneratedProjectModel into = new GeneratedProjectModel();
        into.addSelection(new VocabularySelection("NomineeType"));        // empty
        into.addSelection(vocab("OscarCategories", "Q100", "Q200"));      // authored, non-empty

        int filled = ModelBuilderFrame.mergeBuiltVocabularies(built, into);

        assertEquals(2, filled, "NomineeType filled + WorkGenre created");
        assertEquals(List.of("Q5", "Q11424"),
                ((VocabularySelection) into.findSelection("NomineeType")).valueQids());
        assertEquals(List.of("Q130232"),
                ((VocabularySelection) into.findSelection("WorkGenre")).valueQids());
        // Authored vocab is left exactly as the user had it.
        assertEquals(List.of("Q100", "Q200"),
                ((VocabularySelection) into.findSelection("OscarCategories")).valueQids());
    }
}
