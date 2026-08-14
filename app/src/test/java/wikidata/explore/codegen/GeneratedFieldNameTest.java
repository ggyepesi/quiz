package wikidata.explore.codegen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneratedFieldNameTest {

    @Test void preservesExistingCamelCasePath() {
        assertEquals("structuredName",
                GeneratedViewableSourceGenerator.sanitizeFieldName("structuredName"));
    }

    @Test void convertsHumanLabelToCamelCase() {
        assertEquals("structuredName",
                GeneratedViewableSourceGenerator.sanitizeFieldName("Structured name"));
    }

    @Test void normalizesAnAcronymToken() {
        assertEquals("url",
                GeneratedViewableSourceGenerator.sanitizeFieldName("URL"));
    }
}
