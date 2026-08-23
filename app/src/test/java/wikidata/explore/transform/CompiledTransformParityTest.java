package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldExpectation;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Phase-3 contract: the compiled-model overloads of the pool transforms
 * behave identically to the editable-model ones on the same input.
 */
class CompiledTransformParityTest {

    private static WikidataDynamicObject obj(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    private static GeneratedProjectModel project(String name) {
        GeneratedProjectModel p = new GeneratedProjectModel();
        p.name(name);
        p.rootClass(new GeneratedClassModel("Root"));
        return p;
    }

    private static String describe(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Collection<?> col) {
            List<String> qids = new ArrayList<>();
            for (Object o : col) {
                qids.add(o instanceof WikidataDynamicObject w
                        ? w.qid() : String.valueOf(o));
            }
            return qids.toString();
        }
        return v instanceof WikidataDynamicObject w ? w.qid() : String.valueOf(v);
    }

    @Test
    void fieldValueRestrictionsPruneIdentically() {
        GeneratedProjectModel project = project("fvr");
        GeneratedClassModel item = new GeneratedClassModel("Item");
        GeneratedFieldModel target =
                item.addField("target", FieldType.ENTITY, FieldCardinality.COLLECTION);
        target.mapping().allowedQids().add("Q1");
        target.mapping().allowedQids().add("Q2");
        project.addClass(item);

        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);

        List<WikidataDynamicObject> poolRaw = restrictionPool();
        FieldValueRestrictions.apply(project, poolRaw);
        List<WikidataDynamicObject> poolCompiled = restrictionPool();
        FieldValueRestrictions.apply(compiled, poolCompiled);

        assertEquals(poolRaw.size(), poolCompiled.size());
        for (int i = 0; i < poolRaw.size(); i++) {
            assertEquals(describe(poolRaw.get(i).get("target")),
                    describe(poolCompiled.get(i).get("target")),
                    "target[" + i + "] must prune identically");
        }
    }

    private static List<WikidataDynamicObject> restrictionPool() {
        WikidataDynamicObject a = obj("Q100", "A", "Item");
        a.put("target", new ArrayList<>(List.of(
                obj("Q1", "c1", "Category"),
                obj("Q2", "c2", "Category"),
                obj("Q3", "grammy", "Category"))));   // Q3 not allowed → pruned
        WikidataDynamicObject b = obj("Q101", "B", "Item");
        b.put("target", obj("Q3", "grammy", "Category"));   // single, not allowed → removed
        return new ArrayList<>(List.of(a, b));
    }

    @Test
    void yearProjectionDerivationMatches() {
        GeneratedProjectModel project = project("yp");
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        GeneratedFieldModel year =
                nom.addField("year", FieldType.DATE, FieldCardinality.SINGLE);
        year.mapping().subjectField("edition");
        year.mapping().matchValueField("date.year");
        project.addClass(nom);

        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);

        assertEquals(
                ModelYearProjections.derive(project),
                ModelYearProjections.derive(compiled),
                "the projection list must be identical");
    }

    @Test
    void fieldExpectationsCoverageAndDropsMatch() {
        GeneratedProjectModel project = project("fe");
        project.addClass(new GeneratedClassModel("OscarNominations"));
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        GeneratedFieldModel edition =
                nom.addField("edition", FieldType.ENTITY, FieldCardinality.SINGLE);
        edition.expectation(FieldExpectation.REQUIRED);
        GeneratedFieldModel year =
                nom.addField("year", FieldType.DATE, FieldCardinality.SINGLE);
        year.expectation(FieldExpectation.EXPECTED);
        project.addClass(nom);

        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);

        List<WikidataDynamicObject> poolRaw = expectationPool();
        FieldExpectations.Result raw =
                FieldExpectations.apply(project, poolRaw, null);
        List<WikidataDynamicObject> poolCompiled = expectationPool();
        FieldExpectations.Result comp =
                FieldExpectations.apply(compiled, poolCompiled, null);

        assertEquals(raw.coverage(), comp.coverage(),
                "coverage report must match");
        assertEquals(raw.dropped().size(), comp.dropped().size(),
                "dropped count must match");
        assertEquals(poolRaw.size(), poolCompiled.size(),
                "pool size after REQUIRED drops must match");
    }

    private static List<WikidataDynamicObject> expectationPool() {
        WikidataDynamicObject n1 = obj("Q1", "n1", "Nomination");
        n1.put("edition", obj("E1", "ed", "Edition"));
        n1.put("year", "1990");
        WikidataDynamicObject n2 = obj("Q2", "n2", "Nomination");
        // no edition (REQUIRED → dropped), no year (EXPECTED → kept)
        return new ArrayList<>(List.of(n1, n2));
    }

    @Test
    void canonicalizationRenamesIdentically() {
        GeneratedProjectModel project = project("canon");
        project.addClass(new GeneratedClassModel("OscarNominations"));
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        nom.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        CanonicalSpec spec = new CanonicalSpec()
                .displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField("nominee");
        spec.keyFields().add("nominee");
        nom.canonical(spec);   // EXPLICIT spec
        project.addClass(nom);

        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);

        List<WikidataDynamicObject> poolRaw = canonPool();
        Canonicalization.apply(project, poolRaw, null);
        List<WikidataDynamicObject> poolCompiled = canonPool();
        Canonicalization.apply(compiled, poolCompiled, null);

        assertEquals(poolRaw.size(), poolCompiled.size());
        for (int i = 0; i < poolRaw.size(); i++) {
            assertEquals(poolRaw.get(i).getDisplayName(),
                    poolCompiled.get(i).getDisplayName(),
                    "displayName[" + i + "] must be canonicalized identically");
        }
        assertEquals("Valerie Curtin", poolRaw.get(0).getDisplayName(),
                "the explicit FIELD spec actually renamed to the nominee");
    }

    private static List<WikidataDynamicObject> canonPool() {
        WikidataDynamicObject n = obj("Q1", "initial", "Nomination");
        n.put("nominee", obj("Q9", "Valerie Curtin", "Person"));
        return new ArrayList<>(List.of(n));
    }

    @Test
    void modelInvertDerivationMatches() {
        GeneratedProjectModel project = project("inv");
        GeneratedClassModel src = new GeneratedClassModel("OscarNominations");
        GeneratedFieldModel cats = src.addField(
                "categories", FieldType.ENTITY, FieldCardinality.COLLECTION);
        cats.entityClassName("Category");
        cats.mapping().propertyPid("P1411");
        project.addClass(src);

        GeneratedClassModel cat = new GeneratedClassModel("Category");
        GeneratedFieldModel noms = cat.addField(
                "nominees", FieldType.ENTITY, FieldCardinality.COLLECTION);
        noms.entityClassName("OscarNominations");
        noms.mapping().productionKind(FieldProductionKind.INVERT);   // the reverse ref
        noms.mapping().propertyPid("P1411");
        project.addClass(cat);

        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);

        assertEquals(
                ModelInverts.derive(project),
                ModelInverts.derive(compiled),
                "the invert construct list must be identical");
        assertEquals(1, ModelInverts.derive(compiled).size(),
                "it actually found the Category.nominees <- OscarNominations.categories invert");
    }

    @Test
    void companionMatchMarksIdentically() {
        GeneratedProjectModel project = project("comp");
        project.addClass(new GeneratedClassModel("OscarNominations"));
        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        nom.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        nom.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE);
        GeneratedFieldModel won =
                nom.addField("won", FieldType.BOOLEAN, FieldCardinality.SINGLE);
        won.mapping().productionKind(FieldProductionKind.COMPANION_MATCH);
        won.mapping().propertyPid("P166");
        won.mapping().qualifierPid("P1686");
        won.mapping().subjectField("nominee");
        won.mapping().matchValueField("category");
        won.mapping().matchRoleField("forWork");
        project.addClass(nom);

        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);

        // Companion set keyed "Nomination.won" → one winning (nominee, category, forWork).
        Map<String, Set<List<String>>> sets = Map.of(
                "Nomination.won", Set.of(List.of("Q1", "Q100", "Q200")));

        List<WikidataDynamicObject> poolRaw = companionPool();
        CompanionMatch.applyWithSets(project, poolRaw, sets, null);
        List<WikidataDynamicObject> poolCompiled = companionPool();
        CompanionMatch.applyWithSets(compiled, poolCompiled, sets, null);

        for (int i = 0; i < poolRaw.size(); i++) {
            assertEquals(poolRaw.get(i).get("won"), poolCompiled.get(i).get("won"),
                    "won[" + i + "] must be marked identically");
        }
        assertEquals(Boolean.TRUE, poolRaw.get(0).get("won"), "the winner is marked");
        assertEquals(Boolean.FALSE, poolRaw.get(1).get("won"), "the non-winner is not");
    }

    private static List<WikidataDynamicObject> companionPool() {
        WikidataDynamicObject winner = obj("N1", "winner", "Nomination");
        winner.put("nominee", obj("Q1", "nom", "Person"));
        winner.put("category", obj("Q100", "cat", "Category"));
        winner.put("forWork", obj("Q200", "work", "Work"));
        WikidataDynamicObject loser = obj("N2", "loser", "Nomination");
        loser.put("nominee", obj("Q9", "nom2", "Person"));   // not in the companion set
        loser.put("category", obj("Q100", "cat", "Category"));
        loser.put("forWork", obj("Q200", "work", "Work"));
        return new ArrayList<>(List.of(winner, loser));
    }
}
