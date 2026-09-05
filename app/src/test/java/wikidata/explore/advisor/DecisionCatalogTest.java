package wikidata.explore.advisor;

import wikidata.explore.model.EntityBound;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.transform.QualifierLoadConfig;
import wikidata.explore.transform.TransformConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionCatalogTest {

    private static GeneratedFieldModel field(String name, String pid) {
        GeneratedFieldModel f = new GeneratedFieldModel(
                name, FieldType.ENTITY, FieldCardinality.COLLECTION);
        f.mapping().propertyPid(pid);
        return f;
    }

    private static DecisionContext ctx(GeneratedClassModel c, TransformConfig t) {
        GeneratedProjectModel p = new GeneratedProjectModel();
        p.rootClass(c);
        return new DecisionContext(p, c, t);
    }

    @Test void freshClassFirstStepIsMembership() {
        GeneratedClassModel c = new GeneratedClassModel();
        c.className("X");
        StructuralDecision next = DecisionCatalog.next(ctx(c, null));
        assertNotNull(next);
        assertEquals("membership", next.id());
    }

    @Test void oscarMidwayPointsToTypeStructureNext() {
        // Membership + targets + fields set, but no subclass/facet/qualifiers yet.
        GeneratedClassModel c = new GeneratedClassModel();
        c.className("Oscarnominations");
        c.membership(EntityBound.relation("P1411", List.of("Q102427", "Q103916"), false));
        c.fields().add(field("category", "P1411"));
        c.fields().add(field("type", "P31"));

        DecisionContext ctx = ctx(c, null);
        // membership / targets / intrinsic-fields resolved → next is type-structure.
        assertEquals("type-structure", DecisionCatalog.next(ctx).id());

        List<DecisionCatalog.Evaluated> ev = DecisionCatalog.evaluate(ctx);
        assertResolved(ev, "membership", true);
        assertResolved(ev, "targets", true);
        assertResolved(ev, "intrinsic-fields", true);
        assertResolved(ev, "type-structure", false);
        assertResolved(ev, "qualifiers", false);
        assertResolved(ev, "denormalization", false);
    }

    @Test void transformResolvesQualifiersAndDenormalization() {
        GeneratedClassModel c = new GeneratedClassModel();
        c.className("Oscarnominations");
        c.membership(EntityBound.relation("P1411", List.of("Q102427"), false));
        c.fields().add(field("type", "P31"));

        TransformConfig t = new TransformConfig();
        t.qualifierLoads.add(new QualifierLoadConfig(
                "Oscarnominations",
                "P1411",
                "nominations",
                "Nomination",
                "category",
                EntityBound.instancesOf("Q19020"),
                List.of()));
        t.reifies.add(new wikidata.explore.transform.ReifyConstruct(
                "Oscarnominations", "nominations", "Oscar", "source", "value", true));

        List<DecisionCatalog.Evaluated> ev = DecisionCatalog.evaluate(ctx(c, t));
        assertResolved(ev, "qualifiers", true);
        assertResolved(ev, "denormalization", true);
    }

    @Test void singleTypeMembershipSkipsRelationalDecisions() {
        // Plain P31 = Q5 → no relational branches (targets/type-structure/qualifiers).
        GeneratedClassModel c = new GeneratedClassModel();
        c.className("Person");
        c.membership(EntityBound.relation("P31", List.of("Q5"), false));

        List<String> ids = DecisionCatalog.evaluate(ctx(c, null)).stream()
                .map(e -> e.decision().id()).toList();
        assertFalse(ids.contains("targets"), ids.toString());
        assertFalse(ids.contains("qualifiers"), ids.toString());
        assertTrue(ids.contains("membership"), ids.toString());
    }

    private static void assertResolved(
            List<DecisionCatalog.Evaluated> ev, String id, boolean resolved) {
        DecisionCatalog.Evaluated e = ev.stream()
                .filter(x -> x.decision().id().equals(id)).findFirst().orElseThrow();
        assertEquals(resolved, e.resolved(), id);
    }
}
