package wikidata.explore.generation;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.OwnedComponents;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ValuelessConstructedInstanceFinalizationTest {

    @Test void finalizationDropsAnAcquiredOwnedShellWithNoPayload() throws Exception {
        GeneratedProjectModel project = project();
        WikidataDynamicObject star = new WikidataDynamicObject("Q2734961", "Gamma Scuti");
        star.type("Star");
        star.typeKey("Star");
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(star));
        OwnedComponents.Result composed =
                OwnedComponents.apply(project, pool, null, null);
        composed.addTo(pool);

        DomainFinalization.Result result = DomainFinalization.apply(
                project, ProjectModelCompiler.compile(project), pool,
                List.of(), null, null);

        assertEquals(1, result.valuelessParts());
        assertNull(star.get("discovery"));
        assertEquals(List.of(star), pool);
    }

    private static GeneratedProjectModel project() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("constellations-test");
        GeneratedClassModel star = new GeneratedClassModel("Star");
        star.membership(EntityBound.explicit(List.of("Q2734961")));
        GeneratedFieldModel discovery = star.addField(
                "discovery", FieldType.ENTITY, FieldCardinality.SINGLE);
        discovery.entityClassName("Discovery");
        discovery.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);

        GeneratedClassModel component = new GeneratedClassModel("Discovery");
        component.ownedClass(true);
        GeneratedFieldModel date = component.addField(
                "date", FieldType.DATE, FieldCardinality.SINGLE);
        date.mapping().propertyPid("P575");

        project.rootClass(star);
        project.addClass(component);
        return project;
    }
}
