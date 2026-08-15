package wikidata.explore.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        project.renameClass("Name", "BirthName");

        assertEquals("BirthName", project.findClass("BirthName").className());
        assertEquals("BirthName", site.entityClassName(), "the owning site follows");
        assertEquals("BirthName", stageName.baseClassName(), "the base class follows");
        assertEquals("BirthName", statement.statementSource().sourceClassName(),
                "the statement source follows");
        assertEquals("BirthName",
                ((RoleSelection) project.selections().getFirst()).ownerClassName(),
                "the role owner follows");
        assertEquals("BirthName",
                project.entityKindRules().getFirst().className(), "the kind rule follows");
        // …so the class is still produced where it was, rather than orphaned.
        assertEquals(MembershipPattern.OWNED_COMPONENT,
                MembershipPattern.of(project.findClass("BirthName"), project));
    }
}
