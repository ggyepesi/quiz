package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelStatementReificationsTest {

    private static GeneratedFieldModel field(String name, FieldType type,
                                             String pid, String qualifierPid) {
        GeneratedFieldModel f = new GeneratedFieldModel(name, type, FieldCardinality.SINGLE);
        f.mapping().propertyPid(pid);
        f.mapping().qualifierPid(qualifierPid);
        return f;
    }

    @Test void derivesQualifierLoadAndReifyFromAStatementClass() {
        GeneratedClassModel oscar = new GeneratedClassModel("Oscarnominations");

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSourceClass("Oscarnominations");
        nom.instanceMapping().propertyPid("P1411");     // the reified statement property
        nom.instanceMapping().sourceQid("Q19020");      // value-type filter (categories)
        nom.fields().add(field("category", FieldType.ENTITY, "P1411", ""));   // ps: value
        nom.fields().add(field("year", FieldType.DATE, "", "P585"));          // qualifier → YEAR
        nom.fields().add(field("nominee", FieldType.ENTITY, "", "P2453"));    // qualifier → ENTITY (role)

        GeneratedProjectModel p = new GeneratedProjectModel();
        p.rootClass(oscar);
        p.addClass(nom);

        List<ModelStatementReifications.Reification> rs =
                ModelStatementReifications.derive(p);
        assertEquals(1, rs.size());

        QualifierLoadConfig load = rs.get(0).load();
        assertEquals("Oscarnominations", load.entityType());
        assertEquals("P1411", load.propertyPid());
        assertEquals("category", load.valueField());
        assertEquals("Q19020", load.valueTypeQid());
        assertEquals(2, load.qualifiers().size());
        assertTrue(load.qualifiers().stream().anyMatch(
                q -> q.pid().equals("P585") && q.kind() == QualifierLoadConfig.Kind.YEAR));
        assertTrue(load.qualifiers().stream().anyMatch(
                q -> q.pid().equals("P2453") && q.kind() == QualifierLoadConfig.Kind.ENTITY));

        ReifyConstruct reify = rs.get(0).reify();
        assertEquals("Oscarnominations", reify.sourceType());
        assertEquals("Nomination", reify.targetType());
        assertTrue(reify.promote());
        // the ENTITY qualifier becomes a subject-fallback role; dedup spans value+quals
        assertTrue(reify.roles().stream().anyMatch(
                r -> r.field().equals("nominee") && r.fallbackToSource()));
        // Identity = value + entity qualifiers; the DATE qualifier (year) is an
        // attribute, not part of the key.
        assertTrue(reify.dedupBy().containsAll(List.of("category", "nominee")),
                reify.dedupBy().toString());
        assertTrue(!reify.dedupBy().contains("year"),
                "year (DATE) must not be in the dedup key: " + reify.dedupBy());
    }

    @Test void derivedCompanionMatchFieldIsNotAReifyQualifier() {
        // `won` is a COMPANION_MATCH flag whose qualifierPid (P1686) is the
        // companion's role qualifier — NOT a statement qualifier. It must be
        // excluded from the qualifier load and the dedup key (else it would be
        // set to P1686 on the person-side copy but null on the work-side, splitting
        // the two and blocking dedup).
        GeneratedClassModel oscar = new GeneratedClassModel("Oscarnominations");
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSourceClass("Oscarnominations");
        nom.instanceMapping().propertyPid("P1411");
        nom.fields().add(field("category", FieldType.ENTITY, "P1411", ""));
        nom.fields().add(field("forWork", FieldType.ENTITY, "", "P1686"));
        GeneratedFieldModel won = field("won", FieldType.BOOLEAN, "P166", "P1686");
        won.mapping().productionKind(
                wikidata.explore.model.FieldProductionKind.COMPANION_MATCH);
        nom.fields().add(won);

        GeneratedProjectModel p = new GeneratedProjectModel();
        p.rootClass(oscar);
        p.addClass(nom);

        List<ModelStatementReifications.Reification> rs =
                ModelStatementReifications.derive(p);
        assertEquals(1, rs.size());
        assertTrue(rs.get(0).load().qualifiers().stream().noneMatch(
                q -> q.fieldName().equals("won")), "won must not be loaded as a qualifier");
        assertTrue(!rs.get(0).reify().dedupBy().contains("won"),
                "won must not be in the dedup key: " + rs.get(0).reify().dedupBy());
    }

    @Test void noReificationWithoutStatementSource() {
        GeneratedClassModel plain = new GeneratedClassModel("Person");
        plain.instanceMapping().propertyPid("P31");
        plain.instanceMapping().sourceQid("Q5");
        GeneratedProjectModel p = new GeneratedProjectModel();
        p.rootClass(plain);
        assertTrue(ModelStatementReifications.derive(p).isEmpty());
    }
}
