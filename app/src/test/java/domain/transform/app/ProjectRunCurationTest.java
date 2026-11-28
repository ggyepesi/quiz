package domain.transform.app;

import dataset.DomainStorage;
import domain.build.ProjectLoad;
import domain.build.ProjectSave;
import domain.config.FieldCardinality;
import domain.config.GeneratedProjectModel;
import domain.config.GeneratedProjectModelStore;
import domain.curation.CuratedInstance;
import domain.curation.ManualCuration;
import domain.datasource.schema.FieldType;
import domain.runtime.WikidataDynamicObject;
import domain.runtime.WikidataDynamicObjectJsonStore;
import domain.transform.ui.ProjectBacking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRunCurationTest {
    @TempDir Path temporary;

    @Test void savingAnAcceptedRunNeverMakesItsCurationPermanent() throws Exception {
        var storage = DomainStorage.in(temporary.toFile());
        var model = new GeneratedProjectModel();
        model.name("Example");
        model.rootClass().className("Character");
        model.rootClass().seedQids().addAll(List.of("Q1", "Q2"));
        model.rootClass().addField("description", FieldType.TEXT, FieldCardinality.SINGLE);
        var snapshot = storage.snapshotFile(model.name());
        var modelFile = storage.modelFile(model.name());
        new GeneratedProjectModelStore().save(model, modelFile);
        var one = character("Q1", "One", "Generated description");
        var two = character("Q2", "Two", "Other generated description");
        new WikidataDynamicObjectJsonStore().saveWithFieldGraph(List.of(one, two), snapshot, model);
        var curation = ManualCuration.forSnapshot(snapshot);
        curation.put("Q1", "description", "Accepted description");
        curation.putMerge("Character", "Q1", "Q2", java.util.Map.of());
        curation.putInstance(new CuratedInstance("Character", "character:accepted", "Accepted character",
                List.of(), null));
        curation.save();

        var opened = DomainCatalog.openRun(snapshot, modelFile, storage, ignored -> { });
        var run = opened.loaded().run();
        var view = opened.domain();
        assertEquals("Accepted description", value(view.instances(), "Q1"));
        assertTrue(view.instances().stream().anyMatch(v -> v.getIdentifier().equals("character:accepted")));
        assertFalse(view.instances().stream().anyMatch(v -> v.getIdentifier().equals("Q2")));
        assertEquals("Generated description", value(run.dynamicObjects(), "Q1"));
        assertEquals("Other generated description", value(run.dynamicObjects(), "Q2"));
        assertFalse(run.dynamicObjects().stream().anyMatch(v -> v.getIdentifier().equals("character:accepted")));
        assertEquals("Accepted description",
                value(DomainCatalog.inspectRun(snapshot, model, run).instances(), "Q1"));

        var raw = new ProjectSave.Run(run.dynamicObjects(), run.loadedDeclarations(),
                run.selfReferenceAudit().ledger(), run.modelSnapshot(), run.generatedFromSignature());
        var guard = view.capability(ProjectBacking.class).saveGuard();
        ProjectSave.plan(new ProjectSave.Input(model, raw, opened.loaded().projection().graphDiscovery(),
                List.of()), storage, guard).write();
        assertEquals("Generated description", value(
                ProjectLoad.load(model, storage, snapshot, ignored -> { }).run().dynamicObjects(), "Q1"));

        curation = ManualCuration.forSnapshot(snapshot);
        curation.remove("Q1", "description");
        curation.removeMerge("Character", "Q2");
        curation.removeInstance("Character", "character:accepted");
        curation.save();
        var reopened = DomainCatalog.openRun(snapshot, modelFile, storage, ignored -> { });
        assertEquals("Generated description", value(reopened.domain().instances(), "Q1"));
        assertEquals("Other generated description", value(reopened.domain().instances(), "Q2"));
        assertFalse(reopened.domain().instances().stream()
                .anyMatch(v -> v.getIdentifier().equals("character:accepted")));
    }

    private static Object value(Iterable<? extends objectview.Viewable> values, String id) {
        for (var value : values) {
            if (value.getIdentifier().equals(id)) return objectview.field.FieldSet.of(value).read("description");
        }
        throw new AssertionError("Missing " + id);
    }

    private static WikidataDynamicObject character(String qid, String name, String description) {
        var object = new WikidataDynamicObject(qid, name);
        object.type("Character");
        object.put("description", description);
        return object;
    }
}
