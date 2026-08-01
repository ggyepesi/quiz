package quiz.transform.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.curation.Correction;
import quiz.curation.CorrectionPolicy;
import quiz.curation.ManualCuration;
import quiz.curation.ValueSource;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelFieldRulePromoterTest {

    @TempDir Path temp;

    @Test void promotesOneSourcedFieldWithoutConsumingItsCuration() throws Exception {
        File modelFile = temp.resolve("states.model.json").toFile();
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("States");
        model.rootClass(new GeneratedClassModel("State"));
        new GeneratedProjectModelStore().save(model, modelFile);

        WikidataDynamicObject state = new WikidataDynamicObject("Q1", "One");
        state.type("State");
        state.put("population", 10L);
        SnapshotDomain base = new SnapshotDomain(List.of(state));
        ManualCuration curation = new ManualCuration(
                temp.resolve("states.curation.json").toFile());
        Correction correction = new Correction(
                "State", "Q1", "population", 10L, "wikidata", null,
                CorrectionPolicy.FILL_IF_EMPTY,
                new ValueSource("Wikidata", "Q1", "P1082",
                        "https://www.wikidata.org/wiki/Q1"));
        curation.put("State", "Q1", "population", 10L, "wikidata", null,
                correction.policy(), correction.source());
        curation.save();
        CuratableDomain domain = new CuratableDomain(
                base, curation, base.memberRoots(), List.of(), modelFile);

        var preview = domain.previewPromotion(correction);
        assertTrue(preview.eligible(), preview.reason());
        assertTrue(preview.addsField());
        domain.promote(correction);

        var loaded = new GeneratedProjectModelStore().load(modelFile);
        var promoted = loaded.findClass("State").fields().stream()
                .filter(field -> "population".equals(field.name()))
                .findFirst().orElseThrow();
        assertEquals("P1082", promoted.mapping().propertyPid());
        assertEquals(wikidata.explore.model.RuleDirection.ROOT_TO_ITEM,
                promoted.mapping().direction());
        assertEquals(1, curation.corrections().size(),
                "promotion must retain the reviewed per-instance correction");
        assertFalse(domain.previewPromotion(correction).eligible(),
                "the same model rule is not offered twice");
    }

    @Test void refusesAValueWithoutReusablePropertyEvidence() throws Exception {
        File modelFile = temp.resolve("states.model.json").toFile();
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("State"));
        new GeneratedProjectModelStore().save(model, modelFile);
        WikidataDynamicObject state = new WikidataDynamicObject("Q1", "One");
        state.type("State");
        state.put("population", 10L);
        SnapshotDomain base = new SnapshotDomain(List.of(state));
        CuratableDomain domain = new CuratableDomain(base,
                new ManualCuration(temp.resolve("x.curation.json").toFile()),
                base.memberRoots(), List.of(), modelFile);
        Correction manual = new Correction(
                "State", "Q1", "population", 10L, "manual", null);

        assertFalse(domain.previewPromotion(manual).eligible());
        assertTrue(domain.previewPromotion(manual).reason().contains("source"));
    }
}
