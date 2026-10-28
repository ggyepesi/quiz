package wikidata.explore.generation;

import dataset.DomainStorage;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.model.ImportedPopulationInputs;
import wikidata.explore.model.PopulationSelection;
import wikidata.explore.transform.MaterializedPopulationStore;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves the typed, explicitly published instance boundary of imported models. */
public final class ImportedMaterializedPopulationInputs {
    private final Map<String, List<WikidataDynamicObject>> membersByClass;
    private final Map<String, List<String>> identitiesByClass;

    private ImportedMaterializedPopulationInputs(
            Map<String, List<WikidataDynamicObject>> membersByClass,
            Map<String, List<String>> identitiesByClass) {
        this.membersByClass = Map.copyOf(membersByClass);
        this.identitiesByClass = Map.copyOf(identitiesByClass);
    }

    public static ImportedMaterializedPopulationInputs resolve(
            GeneratedProjectModel consumer, DomainStorage storage) throws Exception {
        if (consumer == null) return new ImportedMaterializedPopulationInputs(
                Map.of(), Map.of());
        DomainStorage source = storage == null ? DomainStorage.inDefaultLocation() : storage;
        ImportedPopulationInputs referenced = ImportedPopulationInputs.of(consumer);
        Map<String, List<WikidataDynamicObject>> result = new LinkedHashMap<>();
        Map<String, List<String>> identities = new LinkedHashMap<>();

        for (GeneratedClassModel clazz : consumer.classes()) {
            if (clazz == null || !clazz.isImported()
                    || !clazz.materializedByTransformation()) continue;
            List<PopulationSelection> selections = referenced.selectionsFor(
                    consumer, clazz.className());
            if (selections.isEmpty()) continue;

            List<WikidataDynamicObject> combined = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            Set<String> publishedIdentities = new LinkedHashSet<>();
            for (PopulationSelection selection : selections) {
                String ownerName = selection.importedFrom();
                File ownerFile = source.modelFileOf(ownerName);
                if (!ownerFile.isFile()) {
                    throw new IllegalStateException("Imported population " + selection.name()
                            + " is published by " + ownerName + " but its model file is missing: "
                            + ownerFile.getPath());
                }
                GeneratedProjectModel owner = new GeneratedProjectModelStore().load(ownerFile);
                GeneratedClassModel outputClass = owner.resolveClass(
                        clazz.declarationId(), clazz.className());
                if (outputClass == null || !outputClass.materializedByTransformation()
                        || outputClass.transformationSource() == null) {
                    throw new IllegalStateException("Imported population " + selection.name()
                            + " names materialized class " + clazz.className()
                            + " which is not produced by " + ownerName);
                }
                wikidata.explore.model.Selection byId = owner.findSelectionById(
                        selection.declarationId());
                wikidata.explore.model.Selection byName = owner.findSelection(
                        selection.name());
                PopulationSelection ownerSelection = byId instanceof PopulationSelection value
                        ? value : byName instanceof PopulationSelection fallback
                                ? fallback : null;
                if (ownerSelection == null
                        || !ownerSelection.className().equals(outputClass.className())) {
                    throw new IllegalStateException("Published population " + selection.name()
                            + " is no longer declared by " + owner.name());
                }
                File artifact = source.publishedPopulationFile(
                        owner.name(), ownerSelection.declarationId());
                if (!artifact.isFile()) {
                    throw new IllegalStateException("Imported population " + selection.name()
                            + " has not been published: " + artifact.getPath());
                }
                MaterializedPopulationStore.Metadata metadata =
                        MaterializedPopulationStore.publication(owner, ownerSelection, source);
                if (metadata == null
                        || !MaterializedPopulationStore.signature(outputClass)
                                .equals(metadata.transformationSignature())
                        || !source.savedSnapshotSignature(owner.name())
                                .equals(metadata.inputSnapshotSignature())) {
                    throw new IllegalStateException("Published population "
                            + selection.name() + " is stale; rebuild " + owner.name()
                            + " before building " + consumer.name());
                }
                Set<String> allowed = new LinkedHashSet<>(ownerSelection.instanceQids());
                publishedIdentities.addAll(allowed);
                Set<String> found = new LinkedHashSet<>();
                for (WikidataDynamicObject object : MaterializedPopulationStore.loadPublished(
                        owner, ownerSelection, source)) {
                    String qid = object == null ? "" : object.getIdentifier();
                    if (!allowed.contains(qid)) continue;
                    found.add(qid);
                    if (!seen.add(qid)) continue;
                    if (!object.directClassNames().contains(clazz.className())) {
                        throw new IllegalStateException("Published " + selection.name()
                                + " contains " + qid + " without class " + clazz.className());
                    }
                    combined.add(object);
                }
                Set<String> missing = new LinkedHashSet<>(allowed);
                missing.removeAll(found);
                if (!missing.isEmpty()) {
                    throw new IllegalStateException("Published population "
                            + selection.name() + " is missing " + missing.size()
                            + " published identities (first: " + missing.iterator().next() + ")");
                }
            }
            result.put(clazz.className(), List.copyOf(combined));
            identities.put(clazz.className(), List.copyOf(publishedIdentities));
        }
        return new ImportedMaterializedPopulationInputs(result, identities);
    }

    public List<WikidataDynamicObject> membersFor(String className) {
        return membersByClass.getOrDefault(className == null ? "" : className.trim(), List.of());
    }

    public List<String> identitiesFor(String className) {
        return identitiesByClass.getOrDefault(
                className == null ? "" : className.trim(), List.of());
    }
}
