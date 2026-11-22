package domain.workbench.modelbuilder.ui;

import domain.datasource.graph.GraphTraversalDirection;
import domain.datasource.graph.RelationRoute;
import domain.datasource.wikidata.WikidataDatasourceProvider;
import domain.datasource.wikidata.WikidataRelationRouteSpec;
import domain.datasource.wikidata.WikidataRouteRelation;
import org.junit.jupiter.api.Test;
import domain.config.FieldCardinality;
import domain.config.FieldSourceBindings;
import domain.config.FieldSourceType;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedFieldModel;
import domain.config.GeneratedProjectModel;

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
                domain.datasource.schema.FieldType.ENTITY, FieldCardinality.COLLECTION);
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
                domain.datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE);
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
                domain.datasource.schema.FieldType.ENTITY, FieldCardinality.COLLECTION);
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
                domain.datasource.schema.FieldType.ENTITY, FieldCardinality.COLLECTION);
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
                        domain.datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE)
                .target().fieldPath());
    }

    private static RelationRoute.Step step(
            WikidataRouteRelation.Kind kind, String pid) {
        return new RelationRoute.Step(new WikidataRouteRelation(kind, pid).relation(),
                GraphTraversalDirection.OUTGOING);
    }
}
