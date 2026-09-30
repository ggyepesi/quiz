package quiz.transform;

import domain.DomainModel;
import objectview.Viewable;
import objectview.field.FieldRef;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationClosureGroupTest {

    @Test void followsMembersThroughAdmittedEntitiesAndStopsAtTheFirstBoundary() {
        DynamicViewable apostolic = value("P1", "Position");
        DynamicViewable king = value("P2", "Position");
        DynamicViewable boundary = value("P3", "Position");
        DynamicViewable unrelated = value("P4", "Position");
        DynamicViewable louis = value("H1", "Person");
        DynamicViewable charles = value("H2", "Person");
        DynamicViewable outsider = value("H3", "Person");

        List<Viewable> holdings = List.of(
                holding("O1", louis, apostolic),
                holding("O2", louis, king),
                holding("O3", charles, king),
                holding("O4", charles, boundary),
                holding("O5", outsider, boundary),
                holding("O6", outsider, unrelated));
        DomainModel domain = domain(
                List.of(apostolic, king, boundary, unrelated, louis, charles, outsider),
                holdings, Map.of("PositionWithHoldersPopulation", List.of(apostolic, king)));
        RelationClosureGroup group = new RelationClosureGroup(
                "Apostolic kings", "Person", "OfficeHolding", "source", "position",
                "PositionWithHoldersPopulation", List.of(apostolic));

        group.reproduce(List.of(louis, charles, outsider), domain);

        assertEquals(List.of("H1", "H2"), group.getMembers().stream()
                .map(Viewable::getIdentifier).toList());
    }

    /** A closure without its boundary would stop at the seeds' own members and look
     *  like a real, smaller answer. It produces nothing and says why instead. */
    @Test void anUnloadedBoundaryProducesNothingAndSaysWhy() {
        DynamicViewable apostolic = value("P1", "Position");
        DynamicViewable louis = value("H1", "Person");
        DomainModel domain = domain(List.of(apostolic, louis),
                List.of(holding("O1", louis, apostolic)), Map.of());
        RelationClosureGroup group = new RelationClosureGroup(
                "Apostolic kings", "Person", "OfficeHolding", "source", "position",
                "PositionWithHoldersPopulation", List.of(apostolic));

        group.reproduce(List.of(louis), domain);

        assertEquals(List.of(), group.getMembers());
        assertEquals("Admission population 'PositionWithHoldersPopulation' is not loaded "
                + "in this domain, so the closure was not computed.", group.problem());
    }

    @Test void anEmptyBoundaryStillAnswersButSaysTheWalkStoppedAtTheSeeds() {
        DynamicViewable apostolic = value("P1", "Position");
        DynamicViewable king = value("P2", "Position");
        DynamicViewable louis = value("H1", "Person");
        DynamicViewable charles = value("H2", "Person");
        DomainModel domain = domain(List.of(apostolic, king, louis, charles),
                List.of(holding("O1", louis, apostolic), holding("O2", louis, king),
                        holding("O3", charles, king)),
                Map.of("PositionWithHoldersPopulation", List.of()));
        RelationClosureGroup group = new RelationClosureGroup(
                "Apostolic kings", "Person", "OfficeHolding", "source", "position",
                "PositionWithHoldersPopulation", List.of(apostolic));

        group.reproduce(List.of(louis, charles), domain);

        assertEquals(List.of("H1"), group.getMembers().stream()
                .map(Viewable::getIdentifier).toList());
        assertTrue(group.problem().contains("has no loaded members"), group.problem());
    }

    @Test void savedRuleKeepsItsConfigurationAndSeedReferences() {
        DynamicViewable seed = value("P1", "Position");
        RelationClosureGroup original = new RelationClosureGroup(
                "Kings", "Person", "OfficeHolding", "source", "position",
                "Positions", List.of(seed));

        RelationClosureGroup restored = assertInstanceOf(RelationClosureGroup.class,
                EditableGroup.copyOf(original));

        assertEquals("OfficeHolding", restored.bridgeType());
        assertEquals("source", restored.memberField());
        assertEquals("position", restored.entityField());
        assertEquals("Positions", restored.admissionSelection());
        assertEquals(List.of(seed), restored.seeds());
    }

    @Test void transformControllerDiscoversTheConfiguredBridgeAndPopulation() {
        DynamicViewable position = value("P1", "Position");
        DynamicViewable person = value("H1", "Person");
        DynamicViewable office = holding("O1", person, position);
        DomainModel domain = domain(List.of(position, person), List.of(office),
                Map.of("PositionWithHoldersPopulation", List.of(position)));
        quiz.transform.ui.TransformController controller =
                new quiz.transform.ui.TransformController(domain, null);

        List<quiz.transform.ui.TransformController.RelationClosureOption> options =
                controller.relationClosureOptions("Person");

        assertEquals(1, options.size());
        assertEquals("OfficeHolding.source ↔ OfficeHolding.position · continue through "
                        + "PositionWithHoldersPopulation", options.getFirst().toString());
        assertEquals(List.of(position), controller.relationClosureSeeds(options.getFirst()));
    }

    /** History's OfficeHolding.source is declared as the role PositionHolder yet holds
     *  Persons, and neither class is a subclass of the other. The option is offered by
     *  what the field holds — the question the group itself asks of each row. */
    @Test void aRoleTypedFieldHoldingTheMemberTypeIsOffered() {
        DynamicViewable position = value("P1", "Position");
        DynamicViewable person = value("H1", "Person");
        DomainModel domain = domain(List.of(position, person),
                List.of(holding("O1", person, position)),
                Map.of("PositionWithHoldersPopulation", List.of(position)), "PositionHolder");
        quiz.transform.ui.TransformController controller =
                new quiz.transform.ui.TransformController(domain, null);

        assertEquals(List.of("OfficeHolding.source ↔ OfficeHolding.position · continue "
                        + "through PositionWithHoldersPopulation"),
                controller.relationClosureOptions("Person").stream()
                        .map(Object::toString).toList());
    }

    private static DynamicViewable value(String id, String type) {
        DynamicViewable value = new DynamicViewable(id, id);
        value.type(type);
        return value;
    }

    private static DynamicViewable holding(String id, Viewable person, Viewable position) {
        DynamicViewable value = value(id, "OfficeHolding");
        value.put("source", person);
        value.put("position", position);
        return value;
    }

    private static DomainModel domain(List<Viewable> entities, List<Viewable> holdings,
            Map<String, List<Viewable>> selections) {
        return domain(entities, holdings, selections, "Person");
    }

    private static DomainModel domain(List<Viewable> entities, List<Viewable> holdings,
            Map<String, List<Viewable>> selections, String sourceTarget) {
        List<Viewable> all = new java.util.ArrayList<>(entities);
        all.addAll(holdings);
        return new DomainModel() {
            @Override public List<String> types() {
                return List.of("Person", "Position", "OfficeHolding");
            }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                if (!"OfficeHolding".equals(type)) return () -> List.of();
                return () -> List.of(reference("source", sourceTarget),
                        reference("position", "Position"));
            }
            @Override public Collection<? extends Viewable> instances() { return all; }
            @Override public List<String> selectionNames() {
                return List.copyOf(selections.keySet());
            }
            @Override public List<Viewable> selectionMembers(String name) {
                return selections.getOrDefault(name, List.of());
            }
            @Override public Class<? extends Viewable> universe() { return Viewable.class; }
        };
    }

    private static FieldRef reference(String name, String target) {
        return FieldRef.described(name, objectview.field.FieldKind.REFERENCE,
                objectview.field.FieldKind.REFERENCE, target, true, false, target,
                false, false, false, false, "", false);
    }
}
