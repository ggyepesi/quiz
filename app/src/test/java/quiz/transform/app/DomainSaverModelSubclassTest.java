package quiz.transform.app;

import domain.DomainModel;
import objectview.Viewable;
import objectview.field.FieldSchema;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A semantic subclass made in TransformApp becomes part of its owning model. */
class DomainSaverModelSubclassTest {

    @Test void newlyAssignedSubclassIsDeclaredWithItsBase() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        DomainModel transformed = new DomainModel() {
            @Override public List<String> types() {
                return List.of("Position", "PositionWithHolders");
            }
            @Override public String baseType(String type) {
                return "PositionWithHolders".equals(type) ? "Position" : null;
            }
            @Override public FieldSchema fieldSchema(String type) {
                return () -> List.of();
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of();
            }
            @Override public Class<? extends Viewable> universe() {
                return Viewable.class;
            }
        };

        DomainSaver.addSubclasses(model, transformed);

        GeneratedClassModel saved = model.findClass("PositionWithHolders");
        assertNotNull(saved);
        assertEquals("Position", saved.baseClassName());
    }

    @Test void filterSubclassKeepsOneExecutableProducerAndPublication() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        wikidata.explore.extract.WikidataDynamicObject position =
                new wikidata.explore.extract.WikidataDynamicObject("Q1", "Position");
        position.type("Position");
        position.put("holderCount", 2);
        DomainModel base = new DomainModel() {
            @Override public List<String> types() { return List.of("Position"); }
            @Override public FieldSchema fieldSchema(String type) {
                return () -> List.of(objectview.field.FieldRef.of("holderCount",
                        objectview.field.FieldKind.ORDERED, "Integer", false, false, true));
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of(position);
            }
            @Override public Class<? extends Viewable> universe() { return Viewable.class; }
        };
        quiz.transform.ui.WorkingDomain working = new quiz.transform.ui.WorkingDomain(base);
        var condition = new quiz.transform.FilterCondition(
                new domain.DomainField("Position", "holderCount", false, false),
                quiz.transform.FilterOperator.GREATER_THAN, 0, null);
        var root = new quiz.transform.EditableGroup("Position");
        root.replaceMembers(List.of(position));
        var group = new quiz.transform.OperationGroup(
                "Has holders", "Position", condition);
        group.reproduce(root.getMembers());
        root.addGroup(group);
        working.defineSubclass("PositionWithHolders", "Position", group.getMembers(), group);

        DomainSaver.addSubclasses(model, working);

        assertEquals(wikidata.explore.model.ClassPopulationMode.MATERIALIZED_TRANSFORM,
                model.findClass("PositionWithHolders").populationMode());
        assertNotNull(model.findClass("PositionWithHolders").transformationSource());
        assertEquals(1, model.findClass("PositionWithHolders")
                .transformationSource().steps().size());
        var population = (wikidata.explore.model.PopulationSelection)
                model.findSelection("PositionWithHoldersPopulation");
        assertNotNull(population);
        assertEquals("PositionWithHolders", population.className());
    }

    /**
     * A filter group filters its PARENT's members. Saving only the leaf's condition made the
     * headless run admit what the parent had already removed: here Q2, which has sitelinks
     * but no holders, was outside the group the author saw and inside the saved producer.
     */
    @Test void aNestedFilterGroupSavesEveryConditionThatProducedIt() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        var held = position("Q1", 2, 9);
        var unheld = position("Q2", 0, 9);
        quiz.transform.ui.WorkingDomain working =
                new quiz.transform.ui.WorkingDomain(positions(held, unheld));
        var root = new quiz.transform.EditableGroup("Position");
        root.replaceMembers(List.of(held, unheld));
        var hasHolders = filter("Has holders", "holderCount", 0);
        hasHolders.reproduce(root.getMembers());
        root.addGroup(hasHolders);
        var wellKnown = filter("Well known", "sitelinkCount", 5);
        wellKnown.reproduce(hasHolders.getMembers());
        hasHolders.addGroup(wellKnown);
        assertEquals(List.of(held), List.copyOf(wellKnown.getMembers()));

        working.defineSubclass("PositionWithHolders", "Position",
                wellKnown.getMembers(), wellKnown);
        DomainSaver.addSubclasses(model, working);

        var steps = model.findClass("PositionWithHolders").transformationSource().steps();
        assertEquals(List.of("holderCount", "sitelinkCount"),
                steps.stream().map(wikidata.explore.model.TransformationStep::fieldPath)
                        .toList(), "root first, each filtering what the previous kept");
        java.util.List<Viewable> replayed = java.util.stream.Stream.of(held, unheld)
                .filter(value -> steps.stream().allMatch(step ->
                        quiz.transform.FilterPredicates.matches(value, step.fieldPath(),
                                step.operator(), step.value(), step.value2())))
                .map(Viewable.class::cast).toList();
        assertEquals(List.of(held), replayed,
                "the saved producer reproduces exactly the group the author saw");
    }

    @Test void aFilterUnderAHandPickedGroupIsNotSavedAsAnExecutableSubclass() {
        var held = position("Q1", 2, 9);
        quiz.transform.ui.WorkingDomain working =
                new quiz.transform.ui.WorkingDomain(positions(held));
        var root = new quiz.transform.EditableGroup("Position");
        root.replaceMembers(List.of(held));
        var chosen = new quiz.transform.EditableGroup("Chosen");
        chosen.replaceMembers(List.of(held));
        root.addGroup(chosen);
        var hasHolders = filter("Has holders", "holderCount", 0);
        hasHolders.reproduce(chosen.getMembers());
        chosen.addGroup(hasHolders);

        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> working.defineSubclass(
                        "PositionWithHolders", "Position", hasHolders.getMembers(), hasHolders));
        org.junit.jupiter.api.Assertions.assertTrue(
                refused.getMessage().contains("Chosen"), refused.getMessage());
        org.junit.jupiter.api.Assertions.assertNull(working.subclassProducer(
                "PositionWithHolders"), "a refused definition records no producer");
    }

    private static wikidata.explore.extract.WikidataDynamicObject position(
            String qid, int holders, int sitelinks) {
        var value = new wikidata.explore.extract.WikidataDynamicObject(qid, "Position");
        value.type("Position");
        value.put("holderCount", holders);
        value.put("sitelinkCount", sitelinks);
        return value;
    }

    private static DomainModel positions(Viewable... members) {
        return new DomainModel() {
            @Override public List<String> types() { return List.of("Position"); }
            @Override public FieldSchema fieldSchema(String type) {
                return () -> List.of(
                        objectview.field.FieldRef.of("holderCount",
                                objectview.field.FieldKind.ORDERED, "Integer", false, false, true),
                        objectview.field.FieldRef.of("sitelinkCount",
                                objectview.field.FieldKind.ORDERED, "Integer", false, false, true));
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of(members);
            }
            @Override public Class<? extends Viewable> universe() { return Viewable.class; }
        };
    }

    private static quiz.transform.OperationGroup filter(String name, String field, int above) {
        return new quiz.transform.OperationGroup(name, "Position",
                new quiz.transform.FilterCondition(
                        new domain.DomainField("Position", field, false, false),
                        quiz.transform.FilterOperator.GREATER_THAN, above, null));
    }

    @Test void manualSubclassStoresOneFrozenPopulationAndReferencesIt() {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.rootClass(new GeneratedClassModel("Position"));
        wikidata.explore.extract.WikidataDynamicObject position =
                new wikidata.explore.extract.WikidataDynamicObject("Q1", "Position");
        position.type("Position");
        DomainModel base = new DomainModel() {
            @Override public List<String> types() { return List.of("Position"); }
            @Override public FieldSchema fieldSchema(String type) { return () -> List.of(); }
            @Override public Collection<? extends Viewable> instances() {
                return List.of(position);
            }
            @Override public Class<? extends Viewable> universe() { return Viewable.class; }
        };
        quiz.transform.ui.WorkingDomain working = new quiz.transform.ui.WorkingDomain(base);
        working.defineManualSubclass("ChosenPosition", "Position", List.of(position),
                "ChosenPositionPopulation");

        DomainSaver.addSubclasses(model, working);

        GeneratedClassModel saved = model.findClass("ChosenPosition");
        var population = (wikidata.explore.model.PopulationSelection)
                model.findSelection("ChosenPositionPopulation");
        assertEquals(wikidata.explore.model.ClassPopulationMode.GENERATED,
                saved.populationMode());
        assertEquals(wikidata.explore.model.SubclassCondition.Kind.IN_POPULATION,
                saved.subclassCondition().kind());
        assertEquals(population.declarationId(), saved.subclassCondition().selectionId());
        assertEquals("Position", population.className());
        assertEquals(List.of("Q1"), population.instanceQids());
    }
}
