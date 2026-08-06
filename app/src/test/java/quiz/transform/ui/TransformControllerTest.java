package quiz.transform.ui;

import org.junit.jupiter.api.Test;
import objectview.Viewable;
import objectview.field.FieldPath;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The headless workbench logic: building the per-type group tree, adding named
 * facet/filter producers under a selected group, and the removal guards — all
 * without any Swing.
 */
class TransformControllerTest {

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
        assertNotNull(facet.getChild("region").getChild("Europe"));
        assertNull(facet.getChild("region").getChild("Africa"));

        // A hand-nested group under a bucket must survive an access with UNCHANGED data.
        quiz.transform.EditableGroup europe =
                (quiz.transform.EditableGroup) facet.getChild("region").getChild("Europe");
        c.addManualGroup(europe, "Manual pick");
        c.groupRoot("City");
        assertNotNull(europe.getChild("Manual pick"),
                "no instance change -> no recompute -> hand edits preserved");

        // The instance set changes online -> the facet recomputes from its rule.
        pool.add(city("Cairo", "Africa"));
        c.groupRoot("City");
        assertNotNull(facet.getChild("region").getChild("Africa"),
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
                facet.getChild("region").getChild("Europe");
        quiz.transform.OperationGroup filtered = c.addFilterGroup(
                "City", europe, "Only Paris",
                new quiz.transform.pipeline.ui.FilterCondition(
                        new DomainField("City", "population", false, false),
                        quiz.transform.pipeline.ui.FilterOperator.EQUALS,
                        1, null));

        assertSame(europe, filtered.getParent());
        assertEquals(List.of("Paris"), filtered.getMembers().stream()
                .map(Viewable::getDisplayName).toList());
        assertNull(facet.getChild("region").getChild("Asia").getChild("Only Paris"));
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
