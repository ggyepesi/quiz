package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AstronomySavedModelTest {
    private static final File MODEL =
            new File("../data/wikidata/astronomy/astronomy.model.json");

    @Test void celestialBodiesStarsAndGalaxiesAreReusableSchemas() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(MODEL);

        assertEquals(GeneratedProjectModel.ProjectKind.MODEL, model.projectKind());
        GeneratedClassModel body = model.findClass("CelestialBody");
        GeneratedClassModel star = model.findClass("Star");
        GeneratedClassModel brightStar = model.findClass("BrightStar");
        GeneratedClassModel namedGalaxyStar = model.findClass("NamedGalaxyStar");
        GeneratedClassModel galaxy = model.findClass("Galaxy");
        assertNotNull(body);
        assertNotNull(star);
        assertNotNull(brightStar);
        assertNotNull(namedGalaxyStar);
        assertNotNull(galaxy);
        assertEquals("CelestialBody", star.baseClassName());
        assertEquals(EntityBound.Kind.UNBOUNDED, star.membership().kind(),
                "Star is the shared schema; its concrete extensions supply populations");
        assertEquals("Star", brightStar.baseClassName());
        assertEquals("Star", namedGalaxyStar.baseClassName());
        assertEquals("CelestialBody", galaxy.baseClassName());
        assertEquals("Discovery", field(body, "discovery").entityClassName());
        assertEquals("P18", field(body, "image").mapping().propertyPid());
        assertEquals("P2120", field(body, "radius").mapping().propertyPid());
        assertEquals("P2386", field(body, "diameter").mapping().propertyPid());
        // One declaration, inherited by every body: three identical copies on the
        // concrete classes could drift apart (a unit changed on one of them).
        GeneratedFieldModel magnitude = field(body, "apparentMagnitude");
        assertEquals("P1215", magnitude.mapping().propertyPid());
        assertEquals("mag", magnitude.unit());
        for (GeneratedClassModel extension : java.util.List.of(
                star, brightStar, namedGalaxyStar, galaxy)) {
            assertTrue(extension.fields().stream()
                            .noneMatch(f -> f.name().equals("apparentMagnitude")),
                    extension.className() + " inherits apparentMagnitude, never redeclares it");
            assertTrue(extension.effectiveFields(model).stream()
                    .anyMatch(f -> f.name().equals("apparentMagnitude")));
        }
        assertEquals("P528", brightStar.membership().relationPid());
        assertTrue(brightStar.membership().qids().isEmpty());
        assertEquals(new wikidata.QualifierCondition("P972", java.util.List.of("Q499138")),
                brightStar.membership().qualifierCondition());

        assertEquals("P31", namedGalaxyStar.membership().relationPid());
        assertEquals(java.util.List.of("Q523"), namedGalaxyStar.membership().qids());
        assertTrue(namedGalaxyStar.membership().includeDescendants());
        assertTrue(namedGalaxyStar.instanceMapping().requireSitelink());

        assertEquals("P31", galaxy.membership().relationPid());
        assertEquals(java.util.List.of("Q318"), galaxy.membership().qids());
        assertTrue(galaxy.membership().includeDescendants());
        assertTrue(galaxy.instanceMapping().requireSitelink());
        assertEquals("P223", field(galaxy, "morphologicalType").mapping().propertyPid());
        assertEquals("P1090", field(galaxy, "redshift").mapping().propertyPid());
        assertEquals("P2583", field(galaxy, "distanceFromEarth").mapping().propertyPid());
        GeneratedFieldModel galaxyStars = field(galaxy, "stars");
        assertEquals("NamedGalaxyStar", galaxyStars.entityClassName());
        assertEquals("P361", galaxyStars.mapping().propertyPid());
        assertEquals(RuleDirection.ITEM_TO_ROOT,
                galaxyStars.mapping().direction());
        assertEquals(FieldProductionKind.CHILD_OBJECTS,
                galaxyStars.mapping().productionKind());
        assertEquals(6, galaxyStars.mapping().limit());
        assertEquals("apparentMagnitude", galaxyStars.sortFieldName());
        assertEquals("Discovery", model.findClass("Discovery").importedFrom());
        EntityRepresentationRule owner = model.entityRepresentationRules().stream()
                .filter(rule -> rule.roleClassName().equals("Owner"))
                .findFirst().orElseThrow();
        assertEquals("CelestialBody", owner.representationClassName());
        assertNotNull(ClassSourceBindings.articleCorrespondence(body));
        assertTrue(GeneratedProjectModelValidator.validate(model).valid());
    }

    private static GeneratedFieldModel field(
            GeneratedClassModel owner, String name) {
        return owner.fields().stream().filter(field -> field.name().equals(name))
                .findFirst().orElseThrow();
    }
}
