package wikidata.explore.workbench;

import datasource.graph.GraphTraversalDirection;
import datasource.graph.RelationRoute;
import datasource.wikidata.WikidataDatasourceProvider;
import datasource.wikidata.WikidataRelationRouteSpec;
import datasource.wikidata.WikidataRouteRelation;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldSourceBindings;
import wikidata.explore.model.FieldSourceType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Discovery is read-only until the field-source editor explicitly accepts a route. */
class DiscoveredRelationRouteApplicationTest {

    @Test
    void addingADiscoveredRouteUsesTheFieldsOrdinaryDatasourceRecipe() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel character = new GeneratedClassModel("Character");
        GeneratedFieldModel child = character.addField("child",
                datasource.schema.FieldType.ENTITY, FieldCardinality.COLLECTION);
        child.entityClassName("Character");
        project.addClass(character);
        project.rootClass(character);
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(project);
        panel.edit(child);

        RelationRoute route = new RelationRoute("qualified-child", "qualified child",
                List.of(step(WikidataRouteRelation.Kind.STATEMENT, "P40"),
                        step(WikidataRouteRelation.Kind.QUALIFIER, "P3831"),
                        step(WikidataRouteRelation.Kind.PROPERTY, "P527")));
        panel.addRelationRoute(route);
        panel.addRelationRoute(route);

        var binding = FieldSourceBindings.binding(child,
                datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE);
        assertEquals(WikidataDatasourceProvider.RELATION_ROUTES,
                binding.recipe().operationId());
        assertEquals(List.of(route), WikidataRelationRouteSpec.parse(
                binding.recipe().parameter(WikidataDatasourceProvider.ROUTES_PARAMETER))
                .routes(), "adding the same discovered route twice is idempotent");
        assertEquals(FieldSourceType.DATASOURCE, child.mapping().sourceType());
    }

    @Test
    void discoveryDoesNotSilentlyReplaceAnExistingDirectPropertySource() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel character = new GeneratedClassModel("Character");
        GeneratedFieldModel child = character.addField("child",
                datasource.schema.FieldType.ENTITY, FieldCardinality.COLLECTION);
        child.entityClassName("Character");
        child.mapping().propertyPid("P40");
        project.addClass(character);
        project.rootClass(character);
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(project);
        panel.edit(child);

        assertTrue(panel.relationRouteUnavailableReason().contains("already reads P40"));
    }

    @Test
    void addingARouteFirstKeepsUnappliedFieldEdits() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel character = new GeneratedClassModel("Character");
        GeneratedFieldModel child = character.addField("child",
                datasource.schema.FieldType.ENTITY, FieldCardinality.COLLECTION);
        child.entityClassName("Character");
        project.addClass(character);
        project.rootClass(character);
        FieldSourcePanel panel = new FieldSourcePanel();
        panel.setProjectModel(project);
        panel.edit(child);
        panel.pendingFieldNameField().setText("offspring");

        RelationRoute route = new RelationRoute("child-route", "child route",
                List.of(step(WikidataRouteRelation.Kind.PROPERTY, "P40")));
        panel.addRelationRoute(route);

        assertEquals("offspring", child.name(),
                "the discovery hand-off must flush the visible editor before reload");
        assertEquals("offspring", FieldSourceBindings.binding(child,
                        datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE)
                .target().fieldPath());
    }

    private static RelationRoute.Step step(
            WikidataRouteRelation.Kind kind, String pid) {
        return new RelationRoute.Step(new WikidataRouteRelation(kind, pid).relation(),
                GraphTraversalDirection.OUTGOING);
    }
}
