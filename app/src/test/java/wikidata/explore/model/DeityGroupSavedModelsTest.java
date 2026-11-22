package wikidata.explore.model;

import datasource.api.SourceBindingSlot;
import datasource.graph.GraphDiscoveryConfiguration;
import datasource.graph.GraphTraversalDirection;
import datasource.graph.constraint.GraphNodeIsUnder;
import datasource.wikidata.WikidataRelationRouteSpec;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeityGroupSavedModelsTest {

    @Test
    void egyptianGroupsAreBoundedAndReferenceEgyptianDeities() throws Exception {
        // Members are typed by the shared base, so a group can hold both deities and the
        // table-row AssessorOfMaat instances; the graph still starts from deities.
        assertDeityGroup("egyptiangods", "DeityGroupMember", "EgyptianDeity",
                "Category:Groups of Egyptian deities");
    }

    @Test
    void greekGroupsAreBoundedAndReferenceMythologicalCharacters() throws Exception {
        assertDeityGroup("mythology", "Character", "Character", "");
    }

    private static void assertDeityGroup(
            String projectName, String memberClassName, String deityClassName,
            String category)
            throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModelStore().load(
                new File("../data/wikidata/" + projectName + "/"
                        + projectName + ".model.json"));
        assertTrue(GeneratedProjectModelValidator.validate(project).valid());

        GeneratedClassModel group = project.findClass("DeityGroup");
        assertNotNull(group);
        assertEquals(List.of(), group.seedQids(),
                "group population is discovered, not a hand-maintained QID list");
        assertEquals("members", group.groupSpec().membersField(),
                "the model explicitly declares group semantics");
        if (category.isBlank()) {
            assertNull(group.populationSource());
            assertTrue(group.sourceBindings().stream().noneMatch(binding ->
                            binding.target().slot() == SourceBindingSlot.CLASS_POPULATION),
                    "the graph is the sole population producer");
        } else {
            assertNotNull(group.populationSource());
            assertEquals("wikipedia", group.populationSource().providerId());
            assertEquals("category-members", group.populationSource().operationId());
            assertEquals(category, group.populationSource().parameter("category"));
            assertTrue(group.sourceBindings().stream().anyMatch(binding ->
                            binding.target().slot() == SourceBindingSlot.CLASS_POPULATION
                                    && "wikipedia".equals(
                                            binding.recipe().providerId())),
                    "the direct category members are the class's ordinary population");
        }

        GeneratedFieldModel members = group.fields().stream()
                .filter(field -> "members".equals(field.name()))
                .findFirst().orElseThrow();
        assertEquals(memberClassName, members.entityClassName());
        assertTrue(project.isSameOrSubclass(deityClassName, memberClassName),
                deityClassName + " instances must be accepted as group members");
        assertEquals(FieldCardinality.COLLECTION, members.cardinality());
        assertEquals(FieldRenderMode.REFERENCE, members.renderMode());
        assertEquals(EdgeMembershipMode.INHERIT, members.edgeMembership());

        var recipe = members.sourceBindings().stream()
                .filter(binding -> binding.target().slot()
                        == SourceBindingSlot.PRIMARY_FIELD_VALUE)
                .findFirst().orElseThrow().recipe();
        assertEquals("relation-routes", recipe.operationId());
        var routes = WikidataRelationRouteSpec.parse(recipe.parameter("routes")).routes();
        assertEquals(List.of("P527", "P361", "P463"), routes.stream()
                .map(route -> route.steps().getFirst().relation().relationId()).toList());
        assertEquals(List.of(
                        GraphTraversalDirection.OUTGOING,
                        GraphTraversalDirection.INCOMING,
                        GraphTraversalDirection.INCOMING),
                routes.stream().map(route -> route.steps().getFirst().direction()).toList());

        GeneratedClassModel discovery = project.findClass("DeityGroupDiscovery");
        assertNotNull(discovery);
        GraphDiscoveryConfiguration graph = discovery.graphSource()
                .configurationFor(discovery.className());
        assertEquals(deityClassName, graph.startNode().qidSourceClass());
        GraphDiscoveryConfiguration.NextNode output = graph.nextNodes().getFirst();
        assertEquals("DeityGroup", output.populationClass());
        assertEquals(GraphDiscoveryConfiguration.PopulationOperation.ADD,
                output.populationOperation());
        assertEquals(List.of("P527", "P361", "P463"), java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(output.property()),
                        output.alternativeEdges().stream()
                                .map(GraphDiscoveryConfiguration.Edge::property))
                .map(datasource.graph.GraphRelation::relationId).toList());
        GraphNodeIsUnder type = (GraphNodeIsUnder) output.evidenceCondition()
                .tests().getFirst();
        assertEquals("Q20830276", type.entity().id());
        assertEquals("P279", type.via().relationId());
        assertTrue(project.buildOperations().stream().anyMatch(operation ->
                operation.kind() == BuildOperation.Kind.RUN_GRAPH_CONSTRAINT
                        && discovery.declarationId().equals(
                                operation.targetDeclarationId())));
    }
}
