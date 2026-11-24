package domain.config;

import domain.datasource.Datasources;
import domain.datasource.api.BindingScope;
import domain.datasource.api.DatasourceOperation;
import domain.datasource.api.DatasourceProvider;
import domain.datasource.api.SourceBindingSlot;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The retired source combo offered names that had no execution path. Every retained
 * legacy choice now either names exactly one registered operation, delegates that choice
 * to its stored binding, or explicitly means that no datasource runs.
 */
class FieldSourceTypeRegistryCoverageTest {

    @Test void everyRetainedLegacyTypeHasOneExplicitDisposition() {
        assertEquals(EnumSet.of(
                        FieldSourceType.SPARQL,
                        FieldSourceType.DBPEDIA,
                        FieldSourceType.WIKIPEDIA_INFOBOX,
                        FieldSourceType.WIKIPEDIA_ARTICLE_INTRODUCTION,
                        FieldSourceType.WIKIPEDIA_ARTICLE_LEAD_PARAGRAPH,
                        FieldSourceType.WIKIPEDIA_ARTICLE_LEAD_SENTENCE,
                        FieldSourceType.WIKIDATA_SITELINK_COUNT,
                        FieldSourceType.WIKIDATA_INCOMING_COUNT,
                        FieldSourceType.WIKIDATA_INHERITED_INCOMING_COUNT),
                types(FieldSourceBindings.LegacySourceKind.REGISTERED_OPERATION));
        assertEquals(EnumSet.of(FieldSourceType.DATASOURCE),
                types(FieldSourceBindings.LegacySourceKind.REGISTRY_BINDING));
        assertEquals(EnumSet.of(FieldSourceType.MANUAL),
                types(FieldSourceBindings.LegacySourceKind.NO_BINDING));
    }

    @Test void everyFixedLegacySourceNamesExactlyOneFieldOperationInTheRegistry() {
        var registry = Datasources.standard();
        for (FieldSourceBindings.LegacySourceCoverage coverage
                : FieldSourceBindings.sourceTypeCoverage()) {
            if (!coverage.registeredOperation()) continue;
            DatasourceProvider provider = registry.provider(coverage.providerId())
                    .orElseThrow(() -> new AssertionError(
                            "Missing provider for " + coverage.sourceType()));
            List<? extends DatasourceOperation> matches = provider.operations().stream()
                    .filter(operation -> operation.id().equals(coverage.operationId()))
                    .toList();
            assertEquals(1, matches.size(), coverage.sourceType().toString());
            assertEquals(BindingScope.FIELD_VALUE, matches.getFirst().scope(),
                    coverage.sourceType().toString());
        }
    }

    @Test void everyCheckedInFieldSourceHasItsClassifiedRegistryPath() throws Exception {
        Path root = Path.of("../data/wikidata");
        List<String> failures = new ArrayList<>();
        int models = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(candidate -> candidate.getFileName().toString()
                            .endsWith(".model.json"))
                    .sorted().toList()) {
                models++;
                GeneratedProjectModel project =
                        new GeneratedProjectModelStore().load(path.toFile());
                checkProject(root.relativize(path).toString(), project, failures);
            }
        }
        assertFalse(models == 0, "no checked-in models found under " + root);
        assertEquals(List.of(), failures);
    }

    /** The binding-to-type direction is a separate hand-written list
     *  ({@code FieldSourceBindings.sourceType(recipe)}). A type added to the coverage
     *  table but not to that list would silently read back as SPARQL or MANUAL. */
    @Test void everyFixedLegacySourceReadsBackAsTheTypeItWasSavedAs() {
        for (FieldSourceBindings.LegacySourceCoverage coverage
                : FieldSourceBindings.sourceTypeCoverage()) {
            if (!coverage.registeredOperation()) continue;
            FieldSourceType type = coverage.sourceType();
            GeneratedProjectModel project = new GeneratedProjectModel();
            GeneratedClassModel root = new GeneratedClassModel("Thing");
            GeneratedFieldModel saved = root.addField("value",
                    domain.datasource.schema.FieldType.STRING, FieldCardinality.SINGLE);
            saved.mapping().sourceType(type);
            saved.mapping().propertyPid("P569");
            project.rootClass(root);
            FieldSourceBindings.synchronizeForSave(project);
            domain.datasource.api.SourceBinding binding = FieldSourceBindings.binding(
                    saved, domain.datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE);
            assertNotNull(binding, type + " produced no binding");
            assertEquals(coverage.providerId(), binding.recipe().providerId(), type.toString());
            assertEquals(coverage.operationId(), binding.recipe().operationId(), type.toString());

            GeneratedFieldModel reread = new GeneratedClassModel("Other").addField("value",
                    domain.datasource.schema.FieldType.STRING, FieldCardinality.SINGLE);
            reread.mapping().sourceType(FieldSourceType.MANUAL);
            FieldSourceBindings.put(reread, binding);
            assertEquals(type, reread.mapping().sourceType(),
                    "the binding of " + type + " reads back as another type");
        }
    }

    @Test void manualMeansNoDatasourceBinding() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel root = new GeneratedClassModel("Thing");
        GeneratedFieldModel note = root.addField("note",
                domain.datasource.schema.FieldType.STRING, FieldCardinality.SINGLE);
        note.mapping().sourceType(FieldSourceType.MANUAL);
        note.mapping().propertyPid("not-a-source");
        project.rootClass(root);

        FieldSourceBindings.synchronizeForSave(project);

        assertTrue(note.sourceBindings().isEmpty());
        assertTrue(FieldSourceType.MANUAL.filledAfterExtraction(),
                "manual values must not enter the generated Wikidata query");
        assertFalse(FieldSourceType.MANUAL.usesPropertyInput());
    }

    private static EnumSet<FieldSourceType> types(
            FieldSourceBindings.LegacySourceKind kind) {
        EnumSet<FieldSourceType> result = EnumSet.noneOf(FieldSourceType.class);
        FieldSourceBindings.sourceTypeCoverage().stream()
                .filter(coverage -> coverage.kind() == kind)
                .map(FieldSourceBindings.LegacySourceCoverage::sourceType)
                .forEach(result::add);
        return result;
    }

    private static void checkProject(String model, GeneratedProjectModel project,
            List<String> failures) {
        for (GeneratedClassModel clazz : project.classes()) {
            if (clazz == null) continue;
            for (GeneratedFieldModel field : clazz.fields()) {
                checkField(model, clazz.className(), "", field, failures);
            }
        }
    }

    private static void checkField(String model, String owner, String parent,
            GeneratedFieldModel field, List<String> failures) {
        if (field == null) return;
        String path = parent.isBlank() ? field.name() : parent + "." + field.name();
        checkMapping(model, owner + "." + path, field.mapping(),
                field.sourceBindings(), SourceBindingSlot.PRIMARY_FIELD_VALUE, failures);
        if (field.fallbackMapping() != null) {
            checkMapping(model, owner + "." + path + " fallback",
                    field.fallbackMapping(), field.sourceBindings(),
                    SourceBindingSlot.FALLBACK_FIELD_VALUE, failures);
        }
        for (OwnedFieldSource override : field.ownedFieldSources()) {
            if (override == null) continue;
            String site = owner + "." + path + " → " + field.entityClassName()
                    + "." + override.fieldPath();
            checkMapping(model, site, override.mapping(),
                    override.sourceBindings(), SourceBindingSlot.PRIMARY_FIELD_VALUE,
                    failures);
            if (override.fallbackMapping() != null) {
                checkMapping(model, site + " fallback", override.fallbackMapping(),
                        override.sourceBindings(),
                        SourceBindingSlot.FALLBACK_FIELD_VALUE, failures);
            }
        }
        for (GeneratedFieldModel child : field.fields()) {
            checkField(model, owner, path, child, failures);
        }
    }

    private static void checkMapping(String model, String location,
            FieldSourceMapping mapping, List<domain.datasource.api.SourceBinding> bindings,
            SourceBindingSlot slot, List<String> failures) {
        assertNotNull(mapping, location);
        FieldSourceBindings.LegacySourceCoverage coverage =
                FieldSourceBindings.coverage(mapping.sourceType());
        if (coverage.kind() == FieldSourceBindings.LegacySourceKind.NO_BINDING) {
            failures.add(model + " — " + location
                    + " persists a manual source instead of curation");
            return;
        }
        if (coverage.kind() != FieldSourceBindings.LegacySourceKind.REGISTRY_BINDING) return;
        List<domain.datasource.api.SourceBinding> chosen = bindings.stream()
                .filter(binding -> binding != null && binding.target().slot()
                        == slot)
                .toList();
        if (chosen.size() != 1) {
            failures.add(model + " — " + location + " has " + chosen.size()
                    + " datasource bindings in " + slot.id());
            return;
        }
        try {
            chosen.getFirst().resolve(Datasources.standard());
        } catch (RuntimeException unresolved) {
            failures.add(model + " — " + location + " — " + unresolved.getMessage());
        }
    }
}
