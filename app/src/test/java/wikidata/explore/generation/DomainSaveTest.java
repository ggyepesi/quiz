package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.VocabularySelection;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What saving a domain would write, and what it would cost.
 *
 * <p>Three rules that decided whether a save was safe, all of them inside a Swing method
 * between the confirmations they fed, none of them runnable: which model actually reaches
 * disk, when a snapshot stops matching the model beside it, and which classes a narrow run
 * would erase from a wider one. The last is the one with a scar — a single-class run
 * silently replacing a multi-class snapshot is how a domain loses its other types.
 */
class DomainSaveTest {

    // ---- what actually reaches disk ------------------------------------------------

    /** A derived vocabulary saved with values is a value that is wrong tomorrow. */
    @Test void aDescriptiveVocabularyIsPersistedAsAnEmptyShell() {
        GeneratedProjectModel model = modelWithDescriptiveVocabulary("NomineeType", "Q5", "Q11424");

        GeneratedProjectModel persisted = DomainSave.persistedModel(model);

        assertTrue(persisted.findSelection("NomineeType") instanceof VocabularySelection,
                "still declared, so a field targeting it still resolves");
        assertEquals(List.of(),
                ((VocabularySelection) persisted.findSelection("NomineeType")).valueQids());
    }

    @Test void strippingForDiskDoesNotTouchTheModelOnScreen() {
        GeneratedProjectModel model = modelWithDescriptiveVocabulary("NomineeType", "Q5");

        DomainSave.persistedModel(model);

        assertEquals(List.of("Q5"),
                ((VocabularySelection) model.findSelection("NomineeType")).valueQids(),
                "an editor is still showing this one");
    }

    /** Only DESCRIPTIVE targets are derived; an authored constraint vocabulary is content. */
    @Test void anAuthoredVocabularyKeepsItsValues() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Nomination"));
        VocabularySelection authored = new VocabularySelection("OscarCategories");
        authored.valueQids(new java.util.ArrayList<>(List.of("Q102427", "Q103360")));
        model.addSelection(authored);

        GeneratedProjectModel persisted = DomainSave.persistedModel(model);

        assertEquals(List.of("Q102427", "Q103360"),
                ((VocabularySelection) persisted.findSelection("OscarCategories")).valueQids());
    }

    // ---- has the model moved on since the run? --------------------------------------

    @Test void aModelSignsForWhatItWouldGenerate() {
        GeneratedProjectModel one = movies("P840");
        GeneratedProjectModel same = movies("P840");
        GeneratedProjectModel edited = movies("P495");

        assertEquals(DomainSave.signature(one), DomainSave.signature(same));
        assertFalse(DomainSave.signature(one).equals(DomainSave.signature(edited)));
    }

    @Test void instancesGeneratedBeforeAnEditWouldBeSavedStale() {
        String fromTheRun = DomainSave.signature(movies("P840"));

        assertTrue(DomainSave.instancesWouldBeStale(fromTheRun, movies("P495")));
        assertFalse(DomainSave.instancesWouldBeStale(fromTheRun, movies("P840")));
    }

    /** Best effort: an unknown signature is not a claim that anything drifted. */
    @Test void anUnknownSignatureMakesNoClaim() {
        assertFalse(DomainSave.instancesWouldBeStale(null, movies("P840")));
        assertFalse(DomainSave.instancesWouldBeStale("", movies("P840")));
        assertFalse(DomainSave.instancesWouldBeStale("whatever", null),
                "a model that cannot be compiled has no signature to disagree with");
    }

    // ---- what a narrow run would erase ----------------------------------------------

    @Test void aSingleClassRunOverAMultiClassSnapshotWouldDropTheRest() {
        List<WikidataDynamicObject> onDisk = List.of(
                object("Q1", "Episode"), object("Q2", "Labour"), object("Q3", "Hero"));
        List<WikidataDynamicObject> run = List.of(object("Q3", "Hero"));

        assertEquals(List.of("Episode", "Labour"), DomainSave.typesDropped(run, onDisk));
    }

    @Test void aRunCoveringEverythingOnDiskDropsNothing() {
        List<WikidataDynamicObject> onDisk = List.of(object("Q1", "Episode"));
        List<WikidataDynamicObject> run = List.of(object("Q1", "Episode"), object("Q9", "Hero"));

        assertEquals(List.of(), DomainSave.typesDropped(run, onDisk),
                "a wider run replacing a narrower snapshot erases nothing");
    }

    @Test void thereIsNothingToDropWhenNothingIsOnDiskYet() {
        assertEquals(List.of(), DomainSave.typesDropped(List.of(object("Q1", "Hero")), null));
        assertEquals(List.of(), DomainSave.typesDropped(null, null));
    }

    /**
     * An unstamped object is not a class. It reads as one if you ask {@code typeName()},
     * which falls back to the carrier's Java class name — the overwrite dialog would then
     * warn that saving drops a type called "WikidataDynamicObject".
     */
    @Test void stampedTypesKeepFirstAppearanceOrderAndSkipTheUnstamped() {
        Set<String> types = DomainSave.stampedTypes(Arrays.asList(
                object("Q1", "Hero"), object("Q2", "Episode"), object("Q3", "Hero"),
                object("Q4", null), object("Q5", "  "), null));

        assertEquals(List.of("Hero", "Episode"), List.copyOf(types));
    }

    @Test void anUnstampedObjectOnDiskIsNotATypeThatWouldBeDropped() {
        List<WikidataDynamicObject> onDisk = List.of(object("Q1", "Episode"), object("Q2", null));

        assertEquals(List.of("Episode"),
                DomainSave.typesDropped(List.of(object("Q9", "Hero")), onDisk));
    }

    // ---- fixtures --------------------------------------------------------------------

    private static GeneratedProjectModel movies(String locationPid) {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel movie = new GeneratedClassModel("Movie");
        movie.instanceMapping().sourceQid("Q11424");
        movie.instanceMapping().propertyPid("P31");
        movie.addField("location", FieldType.TEXT, FieldCardinality.SINGLE)
                .mapping().propertyPid(locationPid);
        model.rootClass(movie);
        return model;
    }

    /** The real shape: Nomination → Nominee (referenced only) → NomineeType, which is a
     *  vocabulary rather than a class, and is therefore derived from the loaded values. */
    private static GeneratedProjectModel modelWithDescriptiveVocabulary(
            String name, String... qids) {
        GeneratedProjectModel model = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.instanceMapping().sourceQid("Q1361864");
        nomination.instanceMapping().propertyPid("P31");
        nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Nominee");
        model.rootClass(nomination);

        GeneratedClassModel nominee = new GeneratedClassModel("Nominee");
        nominee.addField("type", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName(name);
        model.addClass(nominee);

        VocabularySelection vocabulary = new VocabularySelection(name);
        vocabulary.valueQids(new java.util.ArrayList<>(List.of(qids)));
        model.addSelection(vocabulary);
        return model;
    }

    private static WikidataDynamicObject object(String qid, String type) {
        WikidataDynamicObject object = new WikidataDynamicObject(qid, qid);
        if (type != null) object.type(type);
        return object;
    }
}
