package wikidata.explore.view;

import org.junit.jupiter.api.Test;
import quiz.facet.Facet;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFacet;
import wikidata.explore.model.GeneratedFieldModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainFacetsTest {

    @Test void rangeBucketParsesLeadingNumber() {
        assertEquals("2000s", DomainFacets.rangeBucket("2004", 10));
        assertEquals("1990s", DomainFacets.rangeBucket("1999", 10));
        assertEquals("2000–2004", DomainFacets.rangeBucket("2003", 5));
        assertEquals(null, DomainFacets.rangeBucket("no number", 10));
    }

    @Test void firstLetterBuckets() {
        assertEquals("B", DomainFacets.firstLetter("brokeback"));
        assertEquals("A", DomainFacets.firstLetter("aria"));
        assertEquals("#", DomainFacets.firstLetter("21 Grams"));
        assertEquals(null, DomainFacets.firstLetter("  "));
    }

    @Test void suggestsTypeAndTargetFacets() {
        // Oscars-shaped: relation P1411, fields category (P1411) + type (P31).
        GeneratedClassModel c = new GeneratedClassModel();
        c.className("Oscarnominations");
        c.instanceMapping().propertyPid("P1411");

        GeneratedFieldModel cat = new GeneratedFieldModel(
                "category", FieldType.ENTITY, FieldCardinality.COLLECTION);
        cat.mapping().propertyPid("P1411");
        GeneratedFieldModel type = new GeneratedFieldModel(
                "type", FieldType.ENTITY, FieldCardinality.COLLECTION);
        type.mapping().propertyPid("P31");
        c.fields().add(cat);
        c.fields().add(type);

        List<GeneratedFacet> s = DomainFacets.suggestFor(c);
        List<String> fields = s.stream().map(GeneratedFacet::fieldName).toList();
        assertTrue(fields.contains("category"), fields.toString());
        assertTrue(fields.contains("type"), fields.toString());
    }

    @Test void booleanBucketReadsAsFlagNotTrueFalse() {
        assertEquals("Won", DomainFacets.booleanBucket("true", "won"));
        assertEquals("Not won", DomainFacets.booleanBucket("false", "won"));
        assertEquals("Won", DomainFacets.booleanBucket("1", "won"));
        assertEquals(null, DomainFacets.booleanBucket("", "won"));
    }

    @Test void suggestsAFacetForBooleanFields() {
        GeneratedClassModel c = new GeneratedClassModel();
        c.className("Nomination");
        c.fields().add(new GeneratedFieldModel(
                "won", FieldType.BOOLEAN, FieldCardinality.SINGLE));

        List<GeneratedFacet> s = DomainFacets.suggestFor(c);
        assertTrue(s.stream().anyMatch(g -> g.fieldName().equals("won")
                && g.bucketing() == GeneratedFacet.Bucketing.VALUE), s.toString());
    }

    @Test void entityValueFacetUsesReference() {
        GeneratedClassModel c = new GeneratedClassModel();
        c.className("X");
        GeneratedFieldModel cat = new GeneratedFieldModel(
                "category", FieldType.ENTITY, FieldCardinality.COLLECTION);
        c.fields().add(cat);

        Facet f = DomainFacets.toFacet(
                new GeneratedFacet("by category", "category",
                        GeneratedFacet.Bucketing.VALUE), c);
        assertNotNull(f);
        assertEquals("by category", f.label());
    }
}
