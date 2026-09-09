package wikidata.explore.codegen;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldRenderMode;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedViewableSourceTypeTest {
    private final GeneratedViewableSourceGenerator generator =
            new GeneratedViewableSourceGenerator("generated.test");

    @Test void entityClassExtendsTheNeutralGeneratedEntityBase() {
        String source = generator.sourceFor(new GeneratedClassModel("Country"));

        assertTrue(source.contains("extends quiz.source.GeneratedEntity"));
        assertFalse(source.contains("public String qid"));
        assertTrue(source.contains("@objectview.annotations.Label(\"Wikidata source\")"));
        assertTrue(source.contains("public java.util.List<objectview.Viewable> wikidataSource"));
    }

    @Test void inlineRenderModeSurvivesCodeGeneration() {
        GeneratedClassModel person = new GeneratedClassModel("Person");
        var birthName = person.addField(
                "birthName", FieldType.ENTITY, FieldCardinality.SINGLE);
        birthName.entityClassName("Name");
        birthName.renderMode(FieldRenderMode.INLINE);

        String source = generator.sourceFor(person);

        assertTrue(source.contains("import objectview.annotations.Inline;"));
        assertTrue(source.contains("@Inline\n    public objectview.Viewable birthName"));
    }

    @Test void statementClassAlsoExtendsGeneratedEntityNotAnEntityBase() {
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("P1411"));

        String source = generator.sourceFor(nomination);

        // A statement is not an entity: it extends the neutral base, its
        // statement/property provenance living in the anchor, not a superclass.
        assertTrue(source.contains("extends quiz.source.GeneratedEntity"));
        assertFalse(source.contains("extends quiz.source.WikidataSource"));
        assertFalse(source.contains("public String qid"));
        assertTrue(source.contains("FieldRole.PROVENANCE"));
    }

    @Test void anotherDatasourceCanDeclareItsOwnInstanceFieldThroughTheSamePath() {
        datasource.api.DatasourceInstanceField audit =
                new datasource.api.DatasourceInstanceField() {
                    @Override public String name() { return "catalogSource"; }
                    @Override public String label() { return "Catalog source"; }
                    @Override public datasource.api.SourceValueSchema valueSchema() {
                        return new datasource.api.SourceValueSchema(
                                datasource.api.SourceValueKind.URL, false, "");
                    }
                    @Override public Object value(Object source) { return "https://example.test"; }
                };
        datasource.api.DatasourceProvider provider =
                new datasource.api.DatasourceProvider() {
                    @Override public String id() { return "catalog"; }
                    @Override public String displayName() { return "Catalog"; }
                    @Override public List<? extends datasource.api.DatasourceOperation>
                            operations() { return List.of(); }
                    @Override public List<? extends datasource.api.DatasourceInstanceField>
                            instanceFields() { return List.of(audit); }
                };
        GeneratedClassModel item = new GeneratedClassModel("Item");
        item.sourceBindings().add(new datasource.api.SourceBinding(
                datasource.api.SourceBindingTarget.classIdentity("Item"),
                new datasource.api.SourceRecipe("catalog", "identity", java.util.Map.of())));
        var customGenerator = new GeneratedViewableSourceGenerator(
                "generated.test", new datasource.api.DatasourceRegistry(List.of(provider)));

        String source = customGenerator.sourceFor(item);

        assertTrue(source.contains("@objectview.annotations.Label(\"Catalog source\")"));
        assertTrue(source.contains("public String catalogSource"));
        assertFalse(source.contains("wikidataSource"));
    }

    @Test void aStatementRetainsItsWikidataOccurrenceLink() throws Exception {
        GeneratedClassModel fact = new GeneratedClassModel("Fact");
        fact.statementSource(new StatementClassSource("P31"));
        fact.addField("subject", FieldType.STRING, FieldCardinality.SINGLE);
        fact.canonical().keyFields().add("subject");
        var statement = new wikidata.explore.extract.WikidataDynamicObject(
                "Q28$67ADCA97-2FF9-43AD-A4DC-0349086680AC", "statement fact");
        statement.type("Fact");
        statement.put("subject", "Hungary");
        var source = new quiz.source.WikidataStatementSource(
                statement.getIdentifier(), "Q28", "P31", "Q6256", "country");
        statement.addWikidataStatementSource(source);

        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(fact)) {
            quiz.source.GeneratedEntity mapped = (quiz.source.GeneratedEntity)
                    new GeneratedViewableMapper(runtime).mapRoots(List.of(statement)).getFirst();

            assertEquals(List.of("wikidata:Q28$67ADCA97-2FF9-43AD-A4DC-0349086680AC"),
                    mapped.occurrenceIdentities());
            assertEquals(List.of(source), mapped.wikidataStatementSources());
            objectview.field.FieldRef sourceField = objectview.field.FieldSet.of(mapped)
                    .field("wikidataSource");
            assertEquals("Wikidata source", sourceField.label());
            assertEquals(objectview.field.FieldRole.PROVENANCE, sourceField.role());
            assertEquals("https://www.wikidata.org/wiki/Q28", mapped.getUrl());
        }
    }

    @Test void generatedEntityAndStatementSourcesCompileAndMaterialize() throws Exception {
        GeneratedClassModel country = new GeneratedClassModel("Country");
        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(country)) {
            wikidata.explore.extract.WikidataDynamicObject source =
                    new wikidata.explore.extract.WikidataDynamicObject("Q28", "Hungary");
            source.type("Country");
            source.aliases(java.util.List.of("Magyarország"));
            Object mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(java.util.List.of(source)).getFirst();
            assertTrue(mapped instanceof quiz.source.GeneratedEntity);
            assertEquals("Q28", ((objectview.Viewable) mapped).getIdentifier());
            Object provenance = objectview.field.FieldSet.of((objectview.Viewable) mapped)
                    .read("wikidataSource");
            assertEquals("Q28", ((quiz.source.WikidataSource)
                    ((java.util.List<?>) provenance).getFirst()).qid());
            java.lang.reflect.Field aliases = mapped.getClass()
                    .getDeclaredField("alternateNames");
            assertEquals(java.util.List.of("Magyarország"),
                    aliases.get(mapped));
        }

        GeneratedClassModel statementClass = new GeneratedClassModel("Fact");
        statementClass.statementSource(new StatementClassSource("P31"));
        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(statementClass)) {
            wikidata.explore.extract.WikidataDynamicObject statement =
                    new wikidata.explore.extract.WikidataDynamicObject(
                            "Q28$statement-guid", "statement fact");
            statement.type("Fact");
            Object mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(java.util.List.of(statement)).getFirst();
            assertEquals("Q28$statement-guid",
                    ((objectview.Viewable) mapped).getIdentifier());
        }
    }

    @Test void repeatedEntityProjectionsUnionDatasourceSourcesInTheirGeneratedField()
            throws Exception {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        var superClasses = position.addField(
                "superClasses", FieldType.ENTITY, FieldCardinality.COLLECTION);
        superClasses.entityClassName("Position");

        // The same entity first arrives as an untyped field reference, then as its
        // root. This is the shape of Historical Positions: P279 connects members of
        // the population to other members before those members are mapped as roots.
        var referenced = new wikidata.explore.extract.WikidataDynamicObject(
                "Q1", "Referenced position");
        var owner = new wikidata.explore.extract.WikidataDynamicObject("Q2", "Owner");
        owner.type("Position");
        owner.put("superClasses", java.util.List.of(referenced));
        var root = new wikidata.explore.extract.WikidataDynamicObject(
                "Q1", "Referenced position");
        root.type("Position");
        root.addWikidataStatementSource(new quiz.source.WikidataStatementSource(
                "Q2$67ADCA97-2FF9-43AD-A4DC-0349086680AC",
                "Q2", "P279", "Q1", "Position"));

        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(position)) {
            java.util.List<objectview.Viewable> mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(java.util.List.of(owner, root));

            assertEquals(2, mapped.size());
            Object sources = objectview.field.FieldSet.of(mapped.get(1))
                    .read("wikidataSource");
            assertEquals(2, ((java.util.List<?>) sources).size());
            assertTrue(((java.util.List<?>) sources).stream()
                    .anyMatch(quiz.source.WikidataSource.class::isInstance));
            assertTrue(((java.util.List<?>) sources).stream()
                    .anyMatch(quiz.source.WikidataStatementSource.class::isInstance));
        }
    }

    @Test void aSourceContentKeyReducesCandidatesAndRetainsEverySourceIdentity()
            throws Exception {
        GeneratedClassModel person = new GeneratedClassModel("Person");
        person.addField("modeledName", FieldType.STRING, FieldCardinality.SINGLE);
        person.addField("roles", FieldType.STRING, FieldCardinality.COLLECTION);
        person.canonical().keyFields().add("modeledName");

        var first = new wikidata.explore.extract.WikidataDynamicObject("Q1", "First");
        first.type("Person");
        first.put("modeledName", "same person");
        first.put("roles", new java.util.ArrayList<>(java.util.List.of("writer")));
        var second = new wikidata.explore.extract.WikidataDynamicObject("Q2", "Second");
        second.type("Person");
        second.put("modeledName", "same person");
        second.put("roles", new java.util.ArrayList<>(java.util.List.of("director")));

        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(person)) {
            java.util.List<objectview.Viewable> mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(java.util.List.of(first, second));

            assertEquals(1, mapped.size(), "the modeled key, not QID, defines instances");
            quiz.source.GeneratedEntity canonical =
                    (quiz.source.GeneratedEntity) mapped.getFirst();
            assertEquals(java.util.List.of("wikidata:Q1", "wikidata:Q2"),
                    canonical.sourceIdentities(),
                    "reduction must not discard identities needed to revisit the sources");
            assertEquals("https://www.wikidata.org/wiki/Q1", canonical.getUrl(),
                    "a modeled identity must not make its retained source unreachable");
            java.lang.reflect.Field roles = canonical.getClass().getField("roles");
            assertEquals(java.util.List.of("director", "writer"), roles.get(canonical));
        }
    }

    @Test void anExplicitAliasOptOutRemovesTheFieldAndDoesNotCopyCachedAliases()
            throws Exception {
        GeneratedClassModel country = new GeneratedClassModel("Country");
        wikidata.explore.model.ClassSourceBindings.synchronize(country);
        wikidata.explore.model.ClassSourceBindings.aliases(country, false);

        String sourceCode = generator.sourceFor(country);
        assertFalse(sourceCode.contains("alternateNames"));

        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(country)) {
            wikidata.explore.extract.WikidataDynamicObject source =
                    new wikidata.explore.extract.WikidataDynamicObject("Q28", "Hungary");
            source.type("Country");
            source.aliases(java.util.List.of("Magyarország"));
            Object mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(java.util.List.of(source)).getFirst();
            assertFalse(java.util.Arrays.stream(mapped.getClass().getFields())
                    .anyMatch(field -> "alternateNames".equals(field.getName())));
        }
    }

    @Test void roleReferenceMapsToTheClassifiedKindInstance() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel nomination = project.rootClass();
        nomination.className("Nomination");
        nomination.statementSource(new StatementClassSource("P1411"));
        var nomineeField = nomination.addField(
                "nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        nomineeField.entityClassName("Nominee");
        nomineeField.mapping().qualifierPid("P2453");
        project.addClass(new GeneratedClassModel("Nominee"));
        project.addClass(new GeneratedClassModel("Person"));
        project.addEntityKindRule(new EntityKindRule("Person", java.util.List.of("Q5")));

        wikidata.explore.extract.WikidataDynamicObject person =
                new wikidata.explore.extract.WikidataDynamicObject("Q1", "Person One");
        person.type("Person");
        person.typeKey("Person");
        wikidata.explore.extract.WikidataDynamicObject atom =
                new wikidata.explore.extract.WikidataDynamicObject("Q1$guid", "Person One");
        atom.type("Nomination");
        atom.put("nominee", person);

        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(project)) {
            java.util.List<objectview.Viewable> mapped = new GeneratedViewableMapper(runtime)
                    .mapRoots(java.util.List.of(atom, person));
            Object nominationInstance = mapped.getFirst();
            java.lang.reflect.Field field = nominationInstance.getClass()
                    .getDeclaredField("nominee");
            field.setAccessible(true);
            Object referenced = field.get(nominationInstance);

            assertEquals(runtime.forType("Person").generatedClass(), referenced.getClass());
            assertTrue(referenced == mapped.get(1), "field and Person tab share one instance");
        }
    }
}
