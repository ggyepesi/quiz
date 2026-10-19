package wikidata.explore.model;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceCyclesTest {

    @Test void theSavedMythologyModelContainsTheReportedTwoClassCycle()
            throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/mythology/mythology.model.json"));
        GeneratedClassModel character = project.findClass("Character");
        GeneratedClassModel episode = project.findClass("Episode");
        GeneratedFieldModel episodes = field(character, "episodes");
        GeneratedFieldModel characters = field(episode, "characters");

        ReferenceCycles cycles = ReferenceCycles.of(project);

        assertTrue(cycles.onCycle("Character", episodes));
        assertTrue(cycles.onCycle("Episode", characters));
    }

    @Test void aSelfReferenceIsTheOneClassCase() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel spouse = entityField(person, "spouse", "Person");
        project.rootClass(person);

        assertTrue(ReferenceCycles.of(project).onCycle("Person", spouse));
    }

    @Test void everyFieldOnATwoClassCycleIsReported() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel character = new GeneratedClassModel("Character");
        GeneratedFieldModel episodes = entityField(character, "episodes", "Episode");
        project.rootClass(character);
        GeneratedClassModel episode = new GeneratedClassModel("Episode");
        GeneratedFieldModel characters = entityField(episode, "characters", "Character");
        project.addClass(episode);

        ReferenceCycles cycles = ReferenceCycles.of(project);

        assertTrue(cycles.onCycle("Character", episodes));
        assertTrue(cycles.onCycle("Episode", characters));
    }

    @Test void aOneWayReferenceStillProducesItsTargetPopulation() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel character = new GeneratedClassModel("Character");
        GeneratedFieldModel episodes = entityField(character, "episodes", "Episode");
        project.rootClass(character);
        project.addClass(new GeneratedClassModel("Episode"));

        assertFalse(ReferenceCycles.of(project).onCycle("Character", episodes));
    }

    @Test void derivedReferencesDoNotCloseAProductionCycle() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel character = new GeneratedClassModel("Character");
        GeneratedFieldModel episodes = entityField(character, "episodes", "Episode");
        episodes.mapping().productionKind(FieldProductionKind.INVERT);
        project.rootClass(character);
        GeneratedClassModel episode = new GeneratedClassModel("Episode");
        GeneratedFieldModel characters = entityField(episode, "characters", "Character");
        project.addClass(episode);

        ReferenceCycles cycles = ReferenceCycles.of(project);

        assertFalse(cycles.onCycle("Character", episodes));
        assertFalse(cycles.onCycle("Episode", characters));
    }

    @Test void anInheritedCopyHasTheSameCycleAsItsBaseField() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel position = new GeneratedClassModel("Position");
        GeneratedFieldModel superClasses =
                entityField(position, "superClasses", "Position");
        project.rootClass(position);
        GeneratedClassModel withHolders = new GeneratedClassModel("PositionWithHolders");
        withHolders.baseClassName("Position");
        project.addClass(withHolders);

        GeneratedFieldModel inherited = withHolders.effectiveFields(project).stream()
                .filter(field -> field.name().equals("superClasses"))
                .findFirst().orElseThrow();
        ReferenceCycles cycles = ReferenceCycles.of(project);

        assertTrue(cycles.onCycle("Position", superClasses));
        assertTrue(cycles.onCycle("PositionWithHolders", inherited));
    }

    @Test void sameNamedNestedFieldsKeepTheirOwnProductionKind() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel a = new GeneratedClassModel("A");
        GeneratedFieldModel derived = entityField(a, "related", "B");
        derived.mapping().productionKind(FieldProductionKind.INVERT);
        GeneratedFieldModel container = a.addField(
                "details", FieldType.AUTO, FieldCardinality.SINGLE);
        GeneratedFieldModel acquired = new GeneratedFieldModel(
                "related", FieldType.ENTITY, FieldCardinality.COLLECTION);
        acquired.entityClassName("B");
        acquired.mapping().propertyPid("P2");
        container.fields().add(acquired);
        project.rootClass(a);
        GeneratedClassModel b = new GeneratedClassModel("B");
        GeneratedFieldModel back = entityField(b, "owner", "A");
        project.addClass(b);

        ReferenceCycles cycles = ReferenceCycles.of(project);

        assertFalse(cycles.onCycle("A", derived),
                "the top-level inverse does not read another population");
        assertTrue(cycles.onCycle("A", acquired),
                "the acquired nested field with the same leaf name does");
        assertTrue(cycles.onCycle("B", back));
    }

    private static GeneratedFieldModel entityField(
            GeneratedClassModel owner, String name, String target) {
        GeneratedFieldModel field = owner.addField(
                name, FieldType.ENTITY, FieldCardinality.COLLECTION);
        field.entityClassName(target);
        field.mapping().propertyPid("P1");
        return field;
    }

    private static GeneratedFieldModel field(
            GeneratedClassModel owner, String name) {
        return owner.fields().stream().filter(candidate -> candidate.name().equals(name))
                .findFirst().orElseThrow();
    }
}
