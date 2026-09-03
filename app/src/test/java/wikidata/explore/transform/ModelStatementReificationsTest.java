package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.StatementClassSource;
import wikidata.explore.model.StatementCanonicalDefaults;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.QualifierDateMode;
import wikidata.explore.compiled.ProjectModelCompiler;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelStatementReificationsTest {

    private static GeneratedFieldModel field(String name, FieldType type,
                                             String pid, String qualifierPid) {
        GeneratedFieldModel f = new GeneratedFieldModel(name, type, FieldCardinality.SINGLE);
        f.mapping().propertyPid(pid);
        f.mapping().qualifierPid(qualifierPid);
        return f;
    }

    @Test void derivesQualifierLoadAndReifyFromAStatementClass() {
        GeneratedClassModel oscar = new GeneratedClassModel("Oscarnominations");

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("Oscarnominations", "P1411"));
        nom.instanceMapping().propertyPid("P1411");     // the reified statement property
        nom.instanceMapping().sourceQid("Q19020");      // value-type filter (categories)
        nom.fields().add(field("category", FieldType.ENTITY, "P1411", ""));   // ps: value
        nom.fields().add(field("year", FieldType.DATE, "", "P585"));          // qualifier → YEAR
        GeneratedFieldModel nominee = field("nominee", FieldType.ENTITY, "", "P2453");
        nominee.mapping().missingQualifierPolicy(
                wikidata.explore.model.MissingQualifierPolicy.STATEMENT_SUBJECT);
        nom.fields().add(nominee);    // qualifier → ENTITY (explicit subject role)
        StatementCanonicalDefaults.replaceWithSuggestion(nom);

        GeneratedProjectModel p = new GeneratedProjectModel();
        p.rootClass(oscar);
        p.addClass(nom);

        List<ModelStatementReifications.Reification> rs =
                ModelStatementReifications.derive(p);
        assertEquals(1, rs.size());

        QualifierLoadConfig load = rs.get(0).load();
        assertEquals("Oscarnominations", load.entityType());
        assertEquals("P1411", load.propertyPid());
        assertEquals("category", load.valueField());
        assertEquals("Q19020", load.valueTypeQid());
        assertEquals(2, load.qualifiers().size());
        assertTrue(load.qualifiers().stream().anyMatch(
                q -> q.pid().equals("P585") && q.kind() == QualifierLoadConfig.Kind.YEAR));
        assertTrue(load.qualifiers().stream().anyMatch(
                q -> q.pid().equals("P2453") && q.kind() == QualifierLoadConfig.Kind.ENTITY));

        ReifyConstruct reify = rs.get(0).reify();
        assertEquals("Oscarnominations", reify.sourceType());
        assertEquals("Nomination", reify.targetType());
        assertTrue(reify.promote());
        // the explicitly configured ENTITY qualifier becomes a subject-fallback role
        assertTrue(reify.roles().stream().anyMatch(
                r -> r.field().equals("nominee") && r.fallbackToSource()));
        // Identity = value + scalar entity/date qualifiers. The explicit model
        // still projects this legacy field to YEAR, but time remains part of the
        // statement grain.
        assertTrue(reify.dedupBy().containsAll(List.of("category", "nominee", "year")),
                reify.dedupBy().toString());
    }

    @Test void anExplicitStatementSubjectNeedsNoQualifier() {
        GeneratedClassModel prize = new GeneratedClassModel("NobelPrize");
        prize.statementSource(new StatementClassSource("Laureate", "P166"));
        prize.fields().add(field("category", FieldType.ENTITY, "P166", ""));
        GeneratedFieldModel laureate = field(
                "laureate", FieldType.ENTITY, "", "");
        laureate.mapping().productionKind(
                wikidata.explore.model.FieldProductionKind.STATEMENT_SUBJECT);
        laureate.entityClassName("Laureate");
        prize.fields().add(laureate);

        GeneratedClassModel target = new GeneratedClassModel("Laureate");
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(target);
        project.addClass(prize);

        ModelStatementReifications.Reification editable =
                ModelStatementReifications.deriveOne(prize, project);
        var compiled = ProjectModelCompiler.compile(project);
        ModelStatementReifications.Reification immutable =
                ModelStatementReifications.deriveOne(
                        compiled.findClass("NobelPrize").orElseThrow(), compiled);

        for (ModelStatementReifications.Reification result
                : List.of(editable, immutable)) {
            assertEquals("laureate", result.reify().sourceField(),
                    "runtime must write the subject into the compiled subject field, "
                            + "not an undeclared field literally named source");
            assertTrue(result.load().qualifiers().stream().noneMatch(
                    q -> q.fieldName().equals("laureate")));
            assertTrue(result.reify().roles().stream().anyMatch(
                    role -> role.field().equals("laureate")
                            && role.fallbackToSource()
                            && role.kind()
                            == wikidata.explore.model.RoleKind.IDENTITY));
        }
        assertTrue(StatementCanonicalDefaults.suggest(prize)
                .containsAll(List.of("category", "laureate")));
    }

    @Test void englishLanguageQidCompilesToTheLiteralLanguageCode() {
        GeneratedClassModel prize = new GeneratedClassModel("NobelPrize");
        prize.statementSource(new StatementClassSource("Laureate", "P166"));
        prize.fields().add(field("category", FieldType.ENTITY, "P166", ""));
        GeneratedFieldModel laureate = field("laureate", FieldType.ENTITY, "", "");
        laureate.mapping().productionKind(
                wikidata.explore.model.FieldProductionKind.STATEMENT_SUBJECT);
        prize.fields().add(laureate);
        GeneratedFieldModel motivation = field(
                "motivation", FieldType.TEXT, "", "P6208");
        motivation.mapping().valueLanguage("Q1860");
        prize.fields().add(motivation);

        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(new GeneratedClassModel("Laureate"));
        project.addClass(prize);

        QualifierLoadConfig.Qualifier qualifier = ModelStatementReifications
                .deriveOne(prize, project).load().qualifiers().stream()
                .filter(item -> item.fieldName().equals("motivation"))
                .findFirst().orElseThrow();

        assertEquals("en", qualifier.language(),
                "Q1860 and en must configure the same monolingual-text wording");
    }

    @Test void statementParticipantsCompileAsAFieldOperationNotCanonicalPolicy() {
        GeneratedClassModel share = new GeneratedClassModel("LaureateGroup");
        share.statementSource(new StatementClassSource("Laureate", "P166"));
        share.fields().add(field("category", FieldType.ENTITY, "P166", ""));
        GeneratedFieldModel laureates = new GeneratedFieldModel(
                "laureates", FieldType.ENTITY, FieldCardinality.COLLECTION);
        laureates.mapping().qualifierPid("P1706");
        laureates.mapping().productionKind(
                wikidata.explore.model.FieldProductionKind.STATEMENT_PARTICIPANTS);
        share.fields().add(laureates);

        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(new GeneratedClassModel("Laureate"));
        project.addClass(share);

        ModelStatementReifications.Reification result =
                ModelStatementReifications.deriveOne(share, project);

        assertEquals(List.of("laureates"),
                result.reify().subjectParticipantFields());
        assertTrue(result.load().qualifiers().stream().anyMatch(qualifier ->
                qualifier.fieldName().equals("laureates")
                        && qualifier.pid().equals("P1706")
                        && qualifier.multi()));
    }

    @Test void explicitDateProjectionSurvivesEditableAndCompiledDerivation() {
        GeneratedClassModel source = new GeneratedClassModel("People");
        GeneratedClassModel statement = new GeneratedClassModel("Reign");
        statement.statementSource(new StatementClassSource("People", "P39"));
        statement.instanceMapping().propertyPid("P39");
        statement.fields().add(field("position", FieldType.ENTITY, "P39", ""));
        GeneratedFieldModel holder = field("holder", FieldType.ENTITY, "", "");
        holder.mapping().productionKind(
                wikidata.explore.model.FieldProductionKind.STATEMENT_SUBJECT);
        statement.fields().add(holder);
        GeneratedFieldModel start = field("start", FieldType.DATE, "", "P580");
        start.mapping().qualifierDateMode(QualifierDateMode.DATE);
        statement.fields().add(start);

        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(source);
        project.addClass(statement);

        assertTrue(ModelStatementReifications.derive(project).get(0).load()
                .qualifiers().stream().anyMatch(q -> q.fieldName().equals("start")
                        && q.kind() == QualifierLoadConfig.Kind.DATE));

        var compiled = ProjectModelCompiler.compile(project);
        assertTrue(ModelStatementReifications.derive(compiled).get(0).load()
                .qualifiers().stream().anyMatch(q -> q.fieldName().equals("start")
                        && q.kind() == QualifierLoadConfig.Kind.DATE));
    }

    @Test void derivedCompanionMatchFieldIsNotAReifyQualifier() {
        // `won` is a COMPANION_MATCH flag whose qualifierPid (P1686) is the
        // companion's role qualifier — NOT a statement qualifier. It must be
        // excluded from the qualifier load and the dedup key (else it would be
        // set to P1686 on the person-side copy but null on the work-side, splitting
        // the two and blocking dedup).
        GeneratedClassModel oscar = new GeneratedClassModel("Oscarnominations");
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("Oscarnominations", "P1411"));
        nom.instanceMapping().propertyPid("P1411");
        nom.fields().add(field("category", FieldType.ENTITY, "P1411", ""));
        GeneratedFieldModel forWork = field("forWork", FieldType.ENTITY, "", "P1686");
        forWork.mapping().missingQualifierPolicy(
                wikidata.explore.model.MissingQualifierPolicy.STATEMENT_SUBJECT);
        nom.fields().add(forWork);
        GeneratedFieldModel won = field("won", FieldType.BOOLEAN, "P166", "P1686");
        won.mapping().productionKind(
                wikidata.explore.model.FieldProductionKind.COMPANION_MATCH);
        nom.fields().add(won);
        StatementCanonicalDefaults.replaceWithSuggestion(nom);

        GeneratedProjectModel p = new GeneratedProjectModel();
        p.rootClass(oscar);
        p.addClass(nom);

        List<ModelStatementReifications.Reification> rs =
                ModelStatementReifications.derive(p);
        assertEquals(1, rs.size());
        assertTrue(rs.get(0).load().qualifiers().stream().noneMatch(
                q -> q.fieldName().equals("won")), "won must not be loaded as a qualifier");
        assertTrue(!rs.get(0).reify().dedupBy().contains("won"),
                "won must not be in the dedup key: " + rs.get(0).reify().dedupBy());
    }

    @Test void noReificationWithoutStatementSource() {
        GeneratedClassModel plain = new GeneratedClassModel("Person");
        plain.instanceMapping().propertyPid("P31");
        plain.instanceMapping().sourceQid("Q5");
        GeneratedProjectModel p = new GeneratedProjectModel();
        p.rootClass(plain);
        assertTrue(ModelStatementReifications.derive(p).isEmpty());
    }

    @Test void emptyStoredCanonicalKeyIsNotInferredAtRuntime() {
        GeneratedClassModel source = new GeneratedClassModel("Oscarnominations");
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("Oscarnominations", "P1411"));
        nom.fields().add(field("category", FieldType.ENTITY, "P1411", ""));
        GeneratedFieldModel nominee = field("nominee", FieldType.ENTITY, "", "P2453");
        nominee.mapping().missingQualifierPolicy(
                wikidata.explore.model.MissingQualifierPolicy.STATEMENT_SUBJECT);
        nom.fields().add(nominee);
        // Deliberately do not call StatementCanonicalDefaults: this represents an
        // explicitly stored empty/surrogate key, not an old model needing repair.

        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(source);
        project.addClass(nom);

        ModelStatementReifications.Reification editable =
                ModelStatementReifications.deriveOne(nom, project);
        var compiledProject = ProjectModelCompiler.compile(project);
        ModelStatementReifications.Reification compiled =
                ModelStatementReifications.deriveOne(
                        compiledProject.findClass("Nomination").orElseThrow(),
                        compiledProject);

        assertTrue(editable.reify().dedupBy().isEmpty());
        assertTrue(compiled.reify().dedupBy().isEmpty());
    }
}
