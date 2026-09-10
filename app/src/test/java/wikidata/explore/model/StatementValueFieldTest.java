package wikidata.explore.model;

import datasource.schema.FieldType;
import datasource.api.SourceBinding;
import datasource.api.SourceBindingSlot;
import datasource.api.SourceBindingTarget;
import datasource.api.SourceRecipe;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The statement object is a stored field role. Neither a matching PID nor a target
 * class is allowed to invent it.
 */
class StatementValueFieldTest {

    @Test
    void theObjectIsTheFieldExplicitlyLoadedAsTheStatementObject() {
        GeneratedClassModel n = new GeneratedClassModel("Nomination");
        n.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        GeneratedFieldModel category =
                n.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        category.mapping().productionKind(FieldProductionKind.STATEMENT_OBJECT);
        GeneratedFieldModel year =
                n.addField("year", FieldType.DATE, FieldCardinality.SINGLE);
        year.mapping().qualifierPid("P585");           // qualifier

        assertEquals("category", StatementFieldSemantics.statementValueFieldName(n));
        assertTrue(StatementFieldSemantics.isStatementValueField(n, category));
        assertFalse(StatementFieldSemantics.isStatementValueField(n, year),
                "a qualifier is not the value");
    }

    @Test
    void aMatchingPropertyDoesNotImplicitlyMakeAFieldTheObject() {
        GeneratedClassModel n = new GeneratedClassModel("Nomination");
        n.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        n.instanceMapping().propertyPid("P1411");
        GeneratedFieldModel category =
                n.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        category.mapping().propertyPid("P1411");
        n.addField("year", FieldType.DATE, FieldCardinality.SINGLE)
                .mapping().qualifierPid("P585");

        assertEquals("", StatementFieldSemantics.statementValueFieldName(n),
                "a repeated statement PID is not a stored object role");
    }

    @Test
    void anUnambiguousLegacyPropertyIsMigratedOnceToTheStoredRole() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel n = new GeneratedClassModel("Nomination");
        n.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        GeneratedFieldModel category =
                n.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        category.mapping().propertyPid("P1411");
        category.mapping().propertyLabel("nominated for");
        category.sourceBindings().add(new SourceBinding(
                SourceBindingTarget.fieldValue("Nomination", "category",
                        SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new SourceRecipe("wikidata", "property-value",
                        Map.of("property", "P1411"))));
        project.rootClass(n);

        StatementFieldSemantics.migrateLegacyObjectRoles(project);

        assertEquals(FieldProductionKind.STATEMENT_OBJECT,
                category.mapping().productionKind());
        assertEquals("", category.mapping().propertyPid());
        assertTrue(category.sourceBindings().isEmpty());
    }

    @Test
    void ambiguousLegacyObjectFieldsAreNotGuessedAndBlockAcquisition() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel root = new GeneratedClassModel("OscarNominations");
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(
                new StatementClassSource("OscarNominations", "P1411"));
        GeneratedFieldModel subject = nomination.addField(
                "nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        subject.mapping().productionKind(FieldProductionKind.STATEMENT_SUBJECT);
        for (String name : java.util.List.of("category", "otherCategory")) {
            nomination.addField(name, FieldType.ENTITY, FieldCardinality.SINGLE)
                    .mapping().propertyPid("P1411");
        }
        nomination.canonical().keyFields().add("nominee");
        project.rootClass(root);
        project.addClass(nomination);

        StatementFieldSemantics.migrateLegacyObjectRoles(project);

        assertEquals("", StatementFieldSemantics.statementValueFieldName(nomination),
                "migration must not choose between two authored fields");
        var result = GeneratedProjectModelValidator.validate(project);
        assertFalse(result.valid(), result.format());
        assertTrue(result.errors().stream().anyMatch(problem ->
                        problem.message().contains("ambiguous legacy fields")
                                && problem.message().contains("category")
                                && problem.message().contains("otherCategory")
                                && problem.message().contains("P1411")),
                result.format());
    }

    @Test
    void nonReifyClassHasNoValueField() {
        GeneratedClassModel plain = new GeneratedClassModel("OscarNominations");
        plain.addField("target", FieldType.ENTITY, FieldCardinality.COLLECTION);
        assertEquals("", StatementFieldSemantics.statementValueFieldName(plain));
    }
}
