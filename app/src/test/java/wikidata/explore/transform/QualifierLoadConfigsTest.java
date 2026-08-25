package wikidata.explore.transform;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualifierLoadConfigsTest {

    @Test void fieldNameCamelCasesLabel() {
        assertEquals("forWork", QualifierLoadConfigs.fieldName("for work", "P1686"));
        assertEquals("pointInTime", QualifierLoadConfigs.fieldName("point in time", "P585"));
        assertEquals("nominee", QualifierLoadConfigs.fieldName("nominee", "P2453"));
        assertEquals("P9", QualifierLoadConfigs.fieldName("", "P9"));
    }

    @Test void buildsConfigFromDiscoveredQualifiers() {
        List<QualifierLoadConfigs.Discovered> d = List.of(
                new QualifierLoadConfigs.Discovered("P1686", "for work",
                        QualifierLoadConfig.Kind.ENTITY),
                new QualifierLoadConfigs.Discovered("P585", "point in time",
                        QualifierLoadConfig.Kind.YEAR));

        QualifierLoadConfig c = QualifierLoadConfigs.fromQualifiers(
                "Oscarnominations", "P1411", "Q19020", d);

        assertTrue(c.valid());
        assertEquals("Oscarnominations", c.entityType());
        assertEquals("P1411", c.propertyPid());
        assertEquals("Q19020", c.valueTypeQid());
        assertEquals(2, c.qualifiers().size());
        assertEquals("forWork", c.qualifiers().get(0).fieldName());
        assertEquals(QualifierLoadConfig.Kind.YEAR, c.qualifiers().get(1).kind());
    }

    @Test void dropsBookkeepingNoise() {
        List<QualifierLoadConfigs.Discovered> d = List.of(
                new QualifierLoadConfigs.Discovered("P805", "statement is subject of",
                        QualifierLoadConfig.Kind.ENTITY),
                new QualifierLoadConfigs.Discovered("P585", "point in time",
                        QualifierLoadConfig.Kind.YEAR));

        List<QualifierLoadConfigs.Discovered> clean =
                QualifierLoadConfigs.withoutNoise(d);
        assertEquals(1, clean.size());
        assertEquals("P585", clean.get(0).pid());
        assertFalse(clean.stream().anyMatch(x -> x.pid().equals("P805")));
    }

    @Test void suggestTransformProposesLoadPlusCanonicalReify() {
        // The aid: discovery → load + a reify that de-denormalizes (entity quals
        // become roles, dedup over value + entity/year fields).
        List<QualifierLoadConfigs.Discovered> d = List.of(
                new QualifierLoadConfigs.Discovered("P805", "statement is subject of",
                        QualifierLoadConfig.Kind.ENTITY),       // noise → dropped
                new QualifierLoadConfigs.Discovered("P1686", "for work",
                        QualifierLoadConfig.Kind.ENTITY),
                new QualifierLoadConfigs.Discovered("P2453", "nominee",
                        QualifierLoadConfig.Kind.ENTITY),
                new QualifierLoadConfigs.Discovered("P585", "point in time",
                        QualifierLoadConfig.Kind.YEAR));

        TransformConfig tc = QualifierLoadConfigs.suggestTransform(
                "Oscarnominations", "P1411", "Q19020", d);

        assertEquals(1, tc.qualifierLoads.size());
        assertEquals(3, tc.qualifierLoads.get(0).qualifiers().size()); // noise dropped

        assertEquals(1, tc.reifies.size());
        ReifyConstruct r = tc.reifies.get(0);
        assertTrue(r.promote());
        // forWork + nominee become roles (fallback to subject); year/value do not.
        List<String> roleFields = r.roles().stream()
                .map(ReifyConstruct.Role::field).toList();
        assertTrue(roleFields.contains("forWork"), roleFields.toString());
        assertTrue(roleFields.contains("nominee"), roleFields.toString());
        assertTrue(r.roles().stream().allMatch(ReifyConstruct.Role::fallbackToSource));
        // dedup over the main value + every entity/year field.
        assertTrue(r.dedupBy().containsAll(
                List.of("value", "forWork", "nominee", "pointInTime")), r.dedupBy().toString());
    }

    @Test void kindInferenceFromDatatype() {
        assertEquals(QualifierLoadConfig.Kind.ENTITY,
                wikidata.explore.query.logical.DiscoverQualifiersQuery
                        .kindFor("http://wikiba.se/ontology#WikibaseItem"));
        // A time qualifier is discovered as a DATE, not reduced to a year: a reign
        // beginning on 25 December 1000 states a day, and in the Julian calendar.
        // FlexibleDate keeps whatever precision the value states, so DATE loses
        // nothing a source gave; YEAR remains available as a deliberate reduction.
        assertEquals(QualifierLoadConfig.Kind.DATE,
                wikidata.explore.query.logical.DiscoverQualifiersQuery
                        .kindFor("http://wikiba.se/ontology#Time"));
        assertEquals(QualifierLoadConfig.Kind.STRING,
                wikidata.explore.query.logical.DiscoverQualifiersQuery
                        .kindFor("http://wikiba.se/ontology#String"));
    }
}
