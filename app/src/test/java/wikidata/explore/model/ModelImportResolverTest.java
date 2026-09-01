package wikidata.explore.model;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An import is a live reference, not a copy taken once. What the importing project sees
 * is whatever the model says at the moment it is resolved, which is why these tests
 * mutate the model between two resolutions rather than comparing stored bytes.
 */
class ModelImportResolverTest {

    private static GeneratedProjectModel model(String name) {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name(name);
        model.projectKind(GeneratedProjectModel.ProjectKind.MODEL);
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.instanceMapping().sourceQid("Q5");
        person.instanceMapping().propertyPid("P31");
        person.addField("birthName", FieldType.STRING, FieldCardinality.SINGLE);
        model.addClass(person);
        return model;
    }

    private static GeneratedProjectModel domainImporting(String modelName, String... classes) {
        GeneratedProjectModel domain = new GeneratedProjectModel();
        domain.name("Nobel");
        domain.rootClass(new GeneratedClassModel("Prize"));
        domain.addImport(new ModelImport(modelName, List.of(classes)));
        return domain;
    }

    private static ModelImportResolver.Repository repository(GeneratedProjectModel... models) {
        Map<String, GeneratedProjectModel> byName = new HashMap<>();
        for (GeneratedProjectModel model : models) {
            byName.put(model.name().toLowerCase(java.util.Locale.ROOT), model);
        }
        return name -> {
            GeneratedProjectModel found =
                    byName.get(name.toLowerCase(java.util.Locale.ROOT));
            if (found == null) throw new IllegalStateException("Saved model not found: " + name);
            return found;
        };
    }

    /** The whole point: the model moves and every importer moves with it. */
    @Test void whatTheImporterSeesIsWhateverTheModelSaysNow() {
        GeneratedProjectModel people = model("People");
        GeneratedProjectModel domain = domainImporting("People", "Person");

        GeneratedProjectModel before =
                ModelImportResolver.resolve(domain, repository(people));
        assertEquals(1, before.findClass("Person").fields().size());

        people.findClass("Person").addField(
                "deathDate", FieldType.STRING, FieldCardinality.SINGLE);

        GeneratedProjectModel after =
                ModelImportResolver.resolve(domain, repository(people));

        assertEquals(2, after.findClass("Person").fields().size(),
                "a field added to the model reaches the importer with nothing re-imported");
        assertEquals("deathDate", after.findClass("Person").fields().get(1).name());
    }

    /**
     * Only the importer knows about the import; a model is not asked to track who uses
     * it. So a class removed from a model is discovered when an importer next resolves,
     * and this records that it fails legibly — not what the eventual answer should be.
     */
    @Test void aClassRemovedFromTheModelStopsResolving() {
        GeneratedProjectModel people = model("People");
        GeneratedProjectModel domain = domainImporting("People", "Person");
        assertNotNull(ModelImportResolver.resolve(domain, repository(people))
                .findClass("Person"));

        people.removeClass(people.findClass("Person"));

        // What SHOULD happen when a model drops a class its importers still name is
        // deliberately undecided. This pins only that the failure is legible and of the
        // type the loader turns into an IOException, rather than a stack trace escaping
        // load().
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ModelImportResolver.resolve(domain, repository(people)));
        assertTrue(failure.getMessage().contains("People.Person"), failure.getMessage());
    }

    @Test void theAuthoredProjectIsNeverMutatedByResolving() {
        GeneratedProjectModel people = model("People");
        GeneratedProjectModel domain = domainImporting("People", "Person");

        ModelImportResolver.resolve(domain, repository(people));

        assertNull(domain.findClass("Person"),
                "resolution yields an effective model and leaves the authored one alone");
        assertEquals(1, domain.imports().size());
    }

    @Test void aProjectWithoutImportsResolvesToItself() {
        GeneratedProjectModel domain = new GeneratedProjectModel();
        domain.name("Nobel");
        domain.rootClass(new GeneratedClassModel("Prize"));

        GeneratedProjectModel resolved = ModelImportResolver.resolve(domain, null);

        assertNotNull(resolved.findClass("Prize"));
        assertTrue(resolved.imports().isEmpty());
    }

    @Test void aMissingModelIsReportedByName() {
        GeneratedProjectModel domain = domainImporting("People", "Person");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ModelImportResolver.resolve(domain, repository()));

        assertTrue(failure.getMessage().contains("People"), failure.getMessage());
    }

    @Test void aDomainCannotBeImportedFrom() {
        GeneratedProjectModel notAModel = model("People");
        notAModel.projectKind(GeneratedProjectModel.ProjectKind.DOMAIN);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ModelImportResolver.resolve(
                        domainImporting("People", "Person"), repository(notAModel)));

        assertTrue(failure.getMessage().contains("not a model"), failure.getMessage());
    }

    /** A file that no longer holds the model an import names is a mismatch, not a rename. */
    @Test void aResolvedFileMustBeTheModelTheImportNames() {
        GeneratedProjectModel renamed = model("People");
        renamed.name("Humans");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ModelImportResolver.resolve(domainImporting("People", "Person"),
                        name -> renamed));

        assertTrue(failure.getMessage().contains("Humans"), failure.getMessage());
    }

    @Test void aCycleIsRefusedRatherThanFollowedForever() {
        GeneratedProjectModel people = model("People");
        GeneratedProjectModel places = new GeneratedProjectModel();
        places.name("Places");
        places.projectKind(GeneratedProjectModel.ProjectKind.MODEL);
        GeneratedClassModel place = new GeneratedClassModel("Place");
        place.instanceMapping().sourceQid("Q2221906");
        place.instanceMapping().propertyPid("P31");
        places.addClass(place);

        places.addImport(new ModelImport("People", List.of("Person")));
        people.addImport(new ModelImport("Places", List.of("Place")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ModelImportResolver.resolve(
                        domainImporting("People", "Person"), repository(people, places)));

        assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT)
                .contains("cyclic"), failure.getMessage());
    }
}
