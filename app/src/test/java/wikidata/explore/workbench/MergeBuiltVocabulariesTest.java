package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.VocabularySelection;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Folding generation-derived DESCRIPTIVE vocabularies back into the live model:
 *  refresh (overwrite) the descriptive one, never touch an authored constraint vocab. */
class MergeBuiltVocabulariesTest {

    /** A model where Nominee (referenced-only) has a `type` field targeting the
     *  NomineeType vocabulary; category targets the AUTHORED OscarCategories. */
    private static GeneratedProjectModel model() {
        GeneratedProjectModel m = new GeneratedProjectModel();
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("OscarCategories");     // authored constraint target
        m.addClass(nom);
        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        nominee.addField("type", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("NomineeType");         // descriptive target
        m.addClass(nominee);
        m.rootClass(nom);
        return m;
    }

    private static VocabularySelection vocab(String name, String... qids) {
        VocabularySelection v = new VocabularySelection(name);
        v.valueQids(new java.util.ArrayList<>(List.of(qids)));
        return v;
    }

    @Test void refreshesDescriptiveAndLeavesAuthoredAlone() {
        GeneratedProjectModel built = model();
        built.addSelection(vocab("NomineeType", "Q5", "Q11424"));       // freshly derived
        built.addSelection(vocab("OscarCategories", "Q1", "Q2", "Q3")); // authored, unchanged

        GeneratedProjectModel into = model();
        into.addSelection(vocab("NomineeType", "Q515", "Q6256"));       // STALE values
        into.addSelection(vocab("OscarCategories", "Q1", "Q2", "Q3"));  // authored

        int filled = ModelBuilderFrame.mergeBuiltVocabularies(built, into);

        assertEquals(1, filled, "only the descriptive vocab is refreshed");
        assertEquals(List.of("Q5", "Q11424"),
                ((VocabularySelection) into.findSelection("NomineeType")).valueQids(),
                "stale descriptive values are overwritten, not merged");
        assertEquals(List.of("Q1", "Q2", "Q3"),
                ((VocabularySelection) into.findSelection("OscarCategories")).valueQids(),
                "authored constraint vocab is never touched");
    }

    @Test void createsAMissingDescriptiveVocab() {
        GeneratedProjectModel built = model();
        built.addSelection(vocab("NomineeType", "Q5"));

        GeneratedProjectModel into = model();   // no NomineeType selection yet

        assertEquals(1, ModelBuilderFrame.mergeBuiltVocabularies(built, into));
        assertEquals(List.of("Q5"),
                ((VocabularySelection) into.findSelection("NomineeType")).valueQids());
    }
}
