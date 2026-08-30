package wikidata.explore.compiled;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.ProjectModelCompiler.ModelCompilationException;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.ClassKind;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.StatementCanonicalDefaults;

import java.io.File;
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
    void compiledClassesCarryTheirAuthoritativeConstructionKind() {
        GeneratedProjectModel p = project("kinds");
        GeneratedClassModel statement = new GeneratedClassModel("Statement");
        statement.statementSource(new StatementClassSource("Root", "P1411"));
        p.addClass(statement);
        GeneratedClassModel owned = new GeneratedClassModel("Part");
        owned.classKind(ClassKind.OWNED);
        p.addClass(owned);

        CompiledProjectModel compiled = ProjectModelCompiler.compile(p);

        assertEquals(ClassKind.SOURCE, compiled.rootClass().classKind());
        assertEquals(ClassKind.STATEMENT,
                compiled.findClass("Statement").orElseThrow().classKind());
        assertEquals(ClassKind.OWNED,
                compiled.findClass("Part").orElseThrow().classKind());
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
    void compilerPreservesMaterializedCanonicalAndExcludesDerivedFields() {
        GeneratedProjectModel p = project("canon");
        p.addClass(new GeneratedClassModel("OscarNominations"));
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().propertyPid("P1411");
        nom.addField("won", FieldType.BOOLEAN, FieldCardinality.SINGLE)
                .mapping().productionKind(FieldProductionKind.COMPANION_MATCH);
        StatementCanonicalDefaults.replaceWithSuggestion(nom);
        p.addClass(nom);

        CompiledClass cn = ProjectModelCompiler.compile(p)
                .findClass("Nomination").orElseThrow();

        assertTrue(cn.statementClass());
        assertEquals("OscarNominations", cn.statementSource().sourceClassName());
        assertTrue(cn.canonical().keyFields().contains("category"));
        assertFalse(cn.canonical().keyFields().contains("won"),
                "a COMPANION_MATCH field must not enter the compiled key");
    }

    @Test
    void compiledStatementSourceCarriesTheResolvedValueField() {
        GeneratedProjectModel p = project("valuefield");
        p.addClass(new GeneratedClassModel("OscarNominations"));
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().propertyPid("P1411");        // value field on the statement PID
        nom.addField("year", FieldType.DATE, FieldCardinality.SINGLE)
                .mapping().qualifierPid("P585");        // qualifier, not the value
        p.addClass(nom);

        CompiledClass cn = ProjectModelCompiler.compile(p)
                .findClass("Nomination").orElseThrow();

        assertEquals("category", cn.statementSource().valueField(),
                "the value role is resolved once at compile, not at reify");
    }

    @Test
    void compilesAModelWhoseRootWasSplitBySerialization() throws Exception {
        // A saved model serializes its root both as `rootClass` and inside
        // `classes`, so on reload the root is a separate object from its same-named
        // classes entry. The copy the compiler takes must not duplicate it.
        GeneratedProjectModel p = project("split");
        p.rootClass().addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);

        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        File tmp = File.createTempFile("model-split", ".json");
        tmp.deleteOnExit();
        store.save(p, tmp);
        GeneratedProjectModel loaded = store.load(tmp);

        CompiledProjectModel compiled = ProjectModelCompiler.compile(loaded);

        long roots = compiled.classes().stream()
                .filter(c -> c.className().equalsIgnoreCase("Root"))
                .count();
        assertEquals(1, roots,
                "the split root compiles to a single class, not a duplicate");
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

}
