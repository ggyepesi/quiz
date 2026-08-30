package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class name is used BY NAME across the model — a field's target, a base class, a kind
 * rule's subject. Renaming the class alone left those pointing at a name that no longer
 * existed: the field kept a dangling target, and an owned class quietly stopped being
 * produced because no site named it any more.
 */
class RenameClassTest {

    @Test void everyReferenceFollowsTheRename() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("oscars");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel site = person.addField(
                "birthName", FieldType.ENTITY, FieldCardinality.SINGLE);
        site.entityClassName("Name");
        site.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel name = new GeneratedClassModel("Name");
        name.ownedClass(true);
        name.sourceBindings().add(new datasource.api.SourceBinding(
                datasource.api.SourceBindingTarget.classIdentity("Name"),
                new datasource.api.SourceRecipe("wikidata", "identifier", java.util.Map.of())));
        site.sourceBindings().add(new datasource.api.SourceBinding(
                datasource.api.SourceBindingTarget.fieldValue("Name", "birthName",
                        datasource.api.SourceBindingSlot.PRIMARY_FIELD_VALUE),
                new datasource.api.SourceRecipe("wikidata", "property-value",
                        java.util.Map.of("property", "P1"))));
        GeneratedClassModel stageName = new GeneratedClassModel("StageName");
        stageName.baseClassName("Name");
        GeneratedClassModel statement = new GeneratedClassModel("Statement");
        statement.statementSource(new StatementClassSource("Name", "P1"));
        project.rootClass(person);
        project.addClass(name);
        project.addClass(stageName);
        project.addClass(statement);
        project.addSelection(new RoleSelection("names", "Name", "familyName"));
        project.addEntityKindRule(new EntityKindRule("Name", List.of("Q82799")));

        assertTrue(project.renameClass("Name", "BirthName"));

        assertEquals("BirthName", project.findClass("BirthName").className());
        assertEquals("BirthName", site.entityClassName(), "the owning site follows");
        assertEquals("BirthName", stageName.baseClassName(), "the base class follows");
        assertEquals("BirthName", statement.statementSource().sourceClassName(),
                "the statement source follows");
        assertEquals("BirthName", name.sourceBindings().getFirst().target().className(),
                "class datasource bindings follow");
        assertEquals("BirthName", site.sourceBindings().getFirst().target().className(),
                "field datasource bindings follow");
        assertEquals("BirthName",
                ((RoleSelection) project.selections().getFirst()).ownerClassName(),
                "the role owner follows");
        assertEquals("BirthName",
                project.entityKindRules().getFirst().className(), "the kind rule follows");
        // …so the class is still produced where it was, rather than orphaned.
        assertEquals(MembershipPattern.OWNED_COMPONENT,
                MembershipPattern.of(project.findClass("BirthName"), project));
    }

    @Test void aClassCannotTakeAnotherClassOrSelectionsName() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel award = new GeneratedClassModel("Award");
        GeneratedClassModel laureate = new GeneratedClassModel("Laureate");
        project.rootClass(award);
        project.addClass(laureate);
        project.addSelection(new VocabularySelection("Categories"));

        assertFalse(project.renameClass("Award", "Laureate"));
        assertFalse(project.renameClass("Award", "Categories"));
        assertFalse(project.renameClass("Award", ""));

        assertEquals("Award", award.className());
        assertEquals(2, project.classes().size());
        assertEquals("Categories", project.selections().getFirst().name());
    }

    @Test void unchangedNameIsANoOpEvenInALegacyDuplicateNamespace() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel award = new GeneratedClassModel("Award");
        project.rootClass(award);
        project.addSelection(new VocabularySelection("Award"));

        assertTrue(project.renameClass("Award", "Award"));
        assertEquals("Award", award.className());
    }

    @Test void migrationRepairsBindingAddressesWrittenBeforeRenameFollowedBindings() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel award = new GeneratedClassModel("Award");
        award.sourceBindings().add(new datasource.api.SourceBinding(
                datasource.api.SourceBindingTarget.classIdentity("OldAward"),
                new datasource.api.SourceRecipe("wikidata", "identifier", java.util.Map.of())));
        project.rootClass(award);

        project.reconcileSourceBindingTargets();

        assertEquals("Award", award.sourceBindings().getFirst().target().className());
    }
}
