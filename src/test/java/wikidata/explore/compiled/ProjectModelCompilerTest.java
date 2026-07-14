package wikidata.explore.compiled;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.ProjectModelCompiler.ModelCompilationException;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFacet;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectModelCompilerTest {

    private static GeneratedProjectModel project(String name) {
        GeneratedProjectModel p = new GeneratedProjectModel();
        p.name(name);
        p.rootClass(new GeneratedClassModel("Root"));
        return p;
    }

    private static List<String> names(List<CompiledField> fields) {
        return fields.stream().map(CompiledField::name).toList();
    }

    @Test
    void inheritanceIsFlattenedIntoEffectiveFields() {
        GeneratedProjectModel p = project("inherit");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.addField("born", FieldType.ENTITY, FieldCardinality.SINGLE);
        p.addClass(person);
        GeneratedClassModel actor = new GeneratedClassModel("Actor");
        actor.baseClassName("Person");
        actor.addField("won", FieldType.BOOLEAN, FieldCardinality.SINGLE);
        p.addClass(actor);

        CompiledClass ca = ProjectModelCompiler.compile(p)
                .findClass("Actor").orElseThrow();

        assertEquals(List.of("won"), names(ca.ownFields()),
                "ownFields is just the class's own");
        assertTrue(names(ca.effectiveFields()).contains("born"),
                "effectiveFields carries the inherited field");
        assertTrue(names(ca.effectiveFields()).contains("won"));
        assertTrue(ca.field("born").isPresent(), "and is O(1) addressable");
    }

    @Test
    void modeledRefResolvesWhileUnmodeledStaysConfiguredOnly() {
        GeneratedProjectModel p = project("refs");
        p.addClass(new GeneratedClassModel("Category"));
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("Category");
        nom.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("ForWork");                 // not a modeled class
        p.addClass(nom);

        CompiledClass cn = ProjectModelCompiler.compile(p)
                .findClass("Nomination").orElseThrow();

        assertEquals("Category", cn.field("category").orElseThrow().entityClassName());

        CompiledField forWork = cn.field("forWork").orElseThrow();
        assertEquals("ForWork", forWork.configuredEntityClassName());
        assertEquals("", forWork.entityClassName(),
                "an unmodeled ref resolves to blank — the render-as-string signal");
    }

    @Test
    void canonicalIsMaterializedAndExcludesDerivedFields() {
        GeneratedProjectModel p = project("canon");
        p.addClass(new GeneratedClassModel("OscarNominations"));
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        nom.addField("won", FieldType.BOOLEAN, FieldCardinality.SINGLE)
                .mapping().productionKind(FieldProductionKind.COMPANION_MATCH);
        p.addClass(nom);

        CompiledClass cn = ProjectModelCompiler.compile(p)
                .findClass("Nomination").orElseThrow();

        assertTrue(cn.statementClass());
        assertEquals("OscarNominations", cn.statementSource().sourceClassName());
        assertEquals(CanonicalSpec.Kind.DERIVED, cn.canonical().kind());
        assertTrue(cn.canonical().keyFields().contains("category"));
        assertFalse(cn.canonical().keyFields().contains("won"),
                "a COMPANION_MATCH field must not enter the compiled key");
    }

    @Test
    void facetsAreCarried() {
        GeneratedProjectModel p = project("facets");
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        nom.facets().add(new GeneratedFacet(
                "by category", "category", GeneratedFacet.Bucketing.VALUE));
        p.addClass(nom);

        CompiledClass cn = ProjectModelCompiler.compile(p)
                .findClass("Nomination").orElseThrow();

        assertEquals(1, cn.facets().size());
        assertEquals("category", cn.facets().get(0).fieldName());
    }

    @Test
    void anInvalidModelThrowsCarryingTheValidationResult() {
        GeneratedProjectModel p = project("invalid");
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("DoesNotExist", "P1411"));
        p.addClass(nom);

        ModelCompilationException ex = assertThrows(
                ModelCompilationException.class,
                () -> ProjectModelCompiler.compile(p));
        assertFalse(ex.validation().valid());
    }

    @Test
    void aBaseClassCycleIsRejected() {
        GeneratedProjectModel p = project("cycle");
        GeneratedClassModel a = new GeneratedClassModel("Alpha");
        a.baseClassName("Beta");
        GeneratedClassModel b = new GeneratedClassModel("Beta");
        b.baseClassName("Alpha");
        p.addClass(a);
        p.addClass(b);

        ModelCompilationException ex = assertThrows(
                ModelCompilationException.class,
                () -> ProjectModelCompiler.compile(p));
        assertTrue(ex.validation().format().contains("Base class cycle"),
                ex.validation().format());
    }

    @Test
    void compileDoesNotMigrateTheEditableModelInPlace() {
        GeneratedProjectModel p = project("snapshot");
        p.addClass(new GeneratedClassModel("OscarNominations"));
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSourceClass("OscarNominations");        // legacy bridge
        nom.instanceMapping().propertyPid("P1411");
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        p.addClass(nom);

        ProjectModelCompiler.compile(p);

        assertFalse(p.findClass("Nomination").hasExplicitStatementSource(),
                "compile migrates a copy, never the Swing-owned editable");
    }
}
