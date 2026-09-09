package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import objectview.Viewable;
import objectview.field.FieldPath;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import domain.DomainField;
import domain.DomainModel;
import domain.DomainSchemas;

/**
 * The headless workbench logic: building the per-type group tree, adding named
 * facet/filter producers under a selected group, and the removal guards — all
 * without any Swing.
 */
class TransformControllerTest {

    @Test void facetDialogCallsValuePartitionsGroupsRatherThanBuckets() {
        assertArrayEquals(new Object[] {"One group per value", "Present / missing"},
                TransformWorkbenchPanel.facetGroupingChoices());
        assertArrayEquals(new Object[] {"One group per value", "Present / missing",
                        "Nearest selected ancestor"},
                TransformWorkbenchPanel.facetGroupingChoices(true));
    }

    @Test void nearestAncestorCandidatesAndGroupUseTheSelectedReferenceGraph() {
        quiz.transform.DynamicViewable king = city("Q12097", null);
        king.type("Position");
        quiz.transform.DynamicViewable concrete = city("Q1", null);
        concrete.type("Position");
        concrete.put("superClasses", List.of(king));
        DomainField superClasses = new DomainField(
                "Position", "superClasses", true, true);
        DomainModel positions = new DomainModel() {
            @Override public List<String> types() { return List.of("Position"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return () -> List.of(objectview.field.FieldRef.described(
                        "superClasses", objectview.field.FieldKind.COLLECTION,
                        objectview.field.FieldKind.REFERENCE, "List<Position>",
                        true, true, "Position", false, false,
                        false, false, "", false));
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of(concrete, king);
            }
            @Override public Class<? extends Viewable> universe() { return Viewable.class; }
        };
        TransformController controller = new TransformController(positions, null);
        quiz.transform.EditableGroup root = (quiz.transform.EditableGroup)
                controller.groupRoot("Position");

        assertTrue(controller.isRecursiveReference(superClasses));
        assertEquals(List.of("Q12097", "Q1"),
                controller.ancestorCandidates(root, superClasses).stream()
                        .map(candidate -> candidate.value().getDisplayName()).toList());
        assertEquals(List.of(1, 0),
                controller.ancestorCandidates(root, superClasses).stream()
                        .map(TransformController.AncestorCandidate::directChildren).toList());
        quiz.transform.NearestAncestorGroup group = controller.addNearestAncestorGroup(
                "Position", root, "By office", superClasses, List.of(king));
        assertNotNull(group);
        assertEquals(List.of("Q1", "Q12097"),
                group.getChild("Q12097").getMembers().stream()
                        .map(Viewable::getDisplayName).toList());
    }

    @Test void aReferenceToTheOwnersSuperclassIsRecursive() {
        DomainModel hierarchy = new DomainModel() {
            @Override public List<String> types() { return List.of("Position", "Office"); }
            @Override public String baseType(String type) {
                return "Position".equals(type) ? "Office" : null;
            }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                if (!"Position".equals(type)) return () -> List.of();
                return () -> List.of(objectview.field.FieldRef.described(
                        "broaderOffice", objectview.field.FieldKind.REFERENCE,
                        objectview.field.FieldKind.REFERENCE, "Office",
                        true, false, "Office", false, false,
                        false, false, "", false));
            }
            @Override public Collection<? extends Viewable> instances() { return List.of(); }
            @Override public Class<? extends Viewable> universe() { return Viewable.class; }
        };
        TransformController controller = new TransformController(hierarchy, null);

        assertTrue(controller.isRecursiveReference(new DomainField(
                "Position", "broaderOffice", true, false)),
                "specialised instances may climb through a field targeting their base class");
    }

    private static quiz.transform.DynamicViewable city(String name, String region) {
        quiz.transform.DynamicViewable c = new quiz.transform.DynamicViewable(name, name);
        c.type("City");
        c.put("region", region);
        return c;
    }

    @Test void producedGroupsRecomputeWhenInstancesChangeAndSurviveWhenStable() {
        List<Viewable> pool = new java.util.ArrayList<>(List.of(
                city("Paris", "Europe"), city("Tokyo", "Asia")));
        DomainModel cities = new DomainModel() {
            @Override public List<String> types() { return List.of("City"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return DomainSchemas.flatSchema(List.of(
                        new DomainField("City", "region", false, false)));
            }
            @Override public Collection<? extends Viewable> instances() { return pool; }
            @Override public Class<? extends Viewable> universe() { return Viewable.class; }
        };
        TransformController c = new TransformController(cities, null);
        quiz.transform.EditableGroup root =
                (quiz.transform.EditableGroup) c.groupRoot("City");
        quiz.transform.FacetGroup facet =
                c.addFacetGroup("City", root, "Regions",
                        c.field("City", FieldPath.of("region")));
        assertNotNull(facet.getChild("Europe"));
        assertNull(facet.getChild("Africa"));

        // A hand-nested group under a bucket must survive an access with UNCHANGED data.
        quiz.transform.EditableGroup europe =
                (quiz.transform.EditableGroup) facet.getChild("Europe");
        c.addManualGroup(europe, "Manual pick");
        c.groupRoot("City");
        assertNotNull(europe.getChild("Manual pick"),
                "no instance change -> no recompute -> hand edits preserved");

        // The instance set changes online -> the facet recomputes from its rule.
        pool.add(city("Cairo", "Africa"));
        c.groupRoot("City");
        assertNotNull(facet.getChild("Africa"),
                "scope changed -> produced descendants recompute");
        assertEquals(3, root.getMembers().size());
    }

    @Test void realGroupTreeAddsNamedProducersUnderTheSelectedGroup() {
        quiz.transform.DynamicViewable paris = city("Paris", "Europe");
        quiz.transform.DynamicViewable berlin = city("Berlin", "Europe");
        quiz.transform.DynamicViewable tokyo = city("Tokyo", "Asia");
        paris.put("population", 1);
        berlin.put("population", 2);
        tokyo.put("population", 3);
        DomainModel cities = new DomainModel() {
            @Override public List<String> types() { return List.of("City"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return DomainSchemas.flatSchema(List.of(
                        new DomainField("City", "region", false, false),
                        new DomainField("City", "population", false, false)));
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of(paris, berlin, tokyo);
            }
            @Override public Class<? extends Viewable> universe() { return Viewable.class; }
        };
        TransformController c = new TransformController(cities, null);
        quiz.transform.EditableGroup root =
                (quiz.transform.EditableGroup) c.groupRoot("City");
        assertEquals(3, root.getMembers().size());

        quiz.transform.FacetGroup facet = c.addFacetGroup(
                "City", root, "Regions",
                c.field("City", FieldPath.of("region")));
        quiz.transform.EditableGroup europe = (quiz.transform.EditableGroup)
                facet.getChild("Europe");
        quiz.transform.OperationGroup filtered = c.addFilterGroup(
                "City", europe, "Only Paris",
                new quiz.transform.pipeline.ui.FilterCondition(
                        new DomainField("City", "population", false, false),
                        quiz.transform.pipeline.ui.FilterOperator.EQUALS,
                        1, null));

        assertSame(europe, filtered.getParent());
        assertEquals(List.of("Paris"), filtered.getMembers().stream()
                .map(Viewable::getDisplayName).toList());
        assertNull(facet.getChild("Asia").getChild("Only Paris"));
        assertTrue(c.removeGroup("City", filtered));
        assertTrue(europe.getChildren().isEmpty());
        assertFalse(c.removeGroup("City", root));
    }

    @Test void createSubclassFromAnEmptyGroupIsRejected() {
        DomainModel cities = new DomainModel() {
            @Override public List<String> types() { return List.of("City"); }
            @Override public objectview.field.FieldSchema fieldSchema(String type) {
                return DomainSchemas.flatSchema(List.of(
                        new DomainField("City", "region", false, false)));
            }
            @Override public Collection<? extends Viewable> instances() {
                return List.of(city("Paris", "Europe"));
            }
            @Override public Class<? extends Viewable> universe() { return Viewable.class; }
        };
        TransformController c = new TransformController(cities, null);
        quiz.transform.EditableGroup empty = new quiz.transform.EditableGroup("Empty");
        assertThrows(IllegalArgumentException.class,
                () -> c.createSubclassFromGroup("X", "City", empty));
    }
}
