package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.StatementClassSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OscarReifyTest {

    private static WikidataDynamicObject obj(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    @Test void valueQidsInheritedFromSourceMembershipWhenTheFieldHasNone() {
        // The value field has no allowedQids and the class carries a value-TYPE
        // (Q19020) — but Best Picture/Director aren't P31=Q19020, so the type filter
        // would miss them. deriveOne must inherit the SOURCE class's P1411 membership
        // targets (the categories) as the value filter instead.
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel src = new GeneratedClassModel("OscarNominations");
        src.instanceMapping().propertyPid("P1411");
        src.instanceMapping().additionalTypeQids().add("Q102427");   // Best Picture
        src.instanceMapping().additionalTypeQids().add("Q103360");   // Best Director
        project.addClass(src);

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        nom.instanceMapping().propertyPid("P1411");
        nom.instanceMapping().sourceQid("Q19020");   // the wrong value-type filter
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().propertyPid("P1411");     // value field, no allowedQids
        project.addClass(nom);

        ModelStatementReifications.Reification r =
                ModelStatementReifications.deriveOne(nom, project);

        assertNotNull(r);
        assertTrue(r.load().hasValueQids(), "inherits an explicit value set");
        assertTrue(r.load().valueQids().containsAll(List.of("Q102427", "Q103360")),
                "value QIDs inherited from source membership: " + r.load().valueQids());
    }

    @Test void valueFilterGapFlagsAMissedMembershipTarget() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel src = new GeneratedClassModel("OscarNominations");
        src.instanceMapping().propertyPid("P1411");
        src.instanceMapping().additionalTypeQids().add("Q102427");
        src.instanceMapping().additionalTypeQids().add("Q103360");
        project.addClass(src);

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        nom.instanceMapping().propertyPid("P1411");
        var cat = nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        cat.mapping().propertyPid("P1411");
        cat.mapping().allowedQids().add("Q102427");   // explicit, but MISSES Q103360
        project.addClass(nom);

        ModelStatementReifications.Reification r =
                ModelStatementReifications.deriveOne(nom, project);
        assertEquals(List.of("Q103360"),
                ModelStatementReifications.valueFilterGaps(r, project));
    }

    @Test void noValueFilterGapWhenTheFilterCoversMembership() {
        // No explicit allowedQids → deriveOne inherits ALL membership targets → no gap.
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel src = new GeneratedClassModel("OscarNominations");
        src.instanceMapping().propertyPid("P1411");
        src.instanceMapping().additionalTypeQids().add("Q102427");
        src.instanceMapping().additionalTypeQids().add("Q103360");
        project.addClass(src);

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        nom.instanceMapping().propertyPid("P1411");
        nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().propertyPid("P1411");
        project.addClass(nom);

        ModelStatementReifications.Reification r =
                ModelStatementReifications.deriveOne(nom, project);
        assertTrue(ModelStatementReifications.valueFilterGaps(r, project).isEmpty(),
                "inherited value filter covers all membership targets");
    }

    @Test void describeSurfacesSubjectDefaultFieldsAndDedupKey() {
        // The recipe the reify runs, rendered for the log + Statement-class panel:
        // it must make the subject-default fields (the self-reference trap) and the
        // dedup key visible.
        QualifierLoadConfig load = new QualifierLoadConfig(
                "OscarNominations", "P1411", "__Nomination", "Nomination",
                "category", "", List.of(
                        new QualifierLoadConfig.Qualifier(
                                "P805", "edition", QualifierLoadConfig.Kind.ENTITY),
                        new QualifierLoadConfig.Qualifier(
                                "P585", "year", QualifierLoadConfig.Kind.YEAR)));
        ReifyConstruct reify = new ReifyConstruct(
                "OscarNominations", "__Nomination", "Nomination", "source", "value", true,
                List.of(new ReifyConstruct.Role("edition", "edition", true),
                        new ReifyConstruct.Role("nominee", "nominee", true)),
                List.of("category", "edition", "nominee"));
        String desc = ModelStatementReifications.describe(
                new ModelStatementReifications.Reification(load, reify));

        assertTrue(desc.contains("subject-fallback fields: edition, nominee"), desc);
        assertTrue(desc.contains("canonical key: category + edition + nominee"), desc);
        assertTrue(desc.contains("edition←P805"), desc);
        assertTrue(desc.contains("year←P585(date)"), desc);
    }

    @Test void subjectDefaultOffLeavesAnAbsentReferenceEmpty() {
        // The #95 fix mechanism: a plain reference qualifier (edition) with
        // subject-default OFF must NOT collapse to the subject when its qualifier is
        // absent — an absent ceremony stays empty, not the film. (With it ON, the
        // Whale phantom: edition = the film itself.)
        WikidataDynamicObject film = obj("Q1", "The Whale", "Oscarnominations");
        WikidataDynamicObject bare = obj("stmt", "Best Supporting Actress", "Statement");
        film.put("nominations", List.of(bare));   // bare P1411 — no edition qualifier

        ReifyConstruct off = new ReifyConstruct(
                "Oscarnominations", "nominations", "Nomination", "source", "value", true,
                List.of(new ReifyConstruct.Role("edition", "edition", false)),
                List.of("value"));
        new TransformEngine().applyReify(new ArrayList<>(List.of(film)), off);
        assertNull(bare.get("edition"), "subject-default OFF: absent edition stays empty");
        assertSame(film, bare.get("source"));

        // Contrast: ON (the legacy inference) collapses it to the film — the phantom.
        WikidataDynamicObject film2 = obj("Q1", "The Whale", "Oscarnominations");
        WikidataDynamicObject bare2 = obj("stmt", "Best Supporting Actress", "Statement");
        film2.put("nominations", List.of(bare2));
        ReifyConstruct on = new ReifyConstruct(
                "Oscarnominations", "nominations", "Nomination", "source", "value", true,
                List.of(new ReifyConstruct.Role("edition", "edition", true)),
                List.of("value"));
        new TransformEngine().applyReify(new ArrayList<>(List.of(film2)), on);
        assertSame(film2, bare2.get("edition"), "subject-default ON: absent edition = the film");
    }

    @Test void filmAndPersonSidesCollapseToOneNomination() {
        // The same nomination, denormalized onto both endpoints.
        WikidataDynamicObject film = obj("Q214013", "21 Grams", "Oscarnominations");
        WikidataDynamicObject watts = obj("Q132616", "Naomi Watts", "Oscarnominations");
        WikidataDynamicObject bestActress = obj("Q103618", "Best Actress", "Award");

        // Film side: category + nominee (P2453) + year; NO forWork (film IS the work).
        WikidataDynamicObject filmStmt = obj("stmt-film", "Best Actress", "Statement");
        filmStmt.put("category", bestActress);
        filmStmt.put("nominee", watts);
        filmStmt.put("year", 2004);
        film.put("nominations", List.of(filmStmt));

        // Person side: category + forWork (P1686) + year; NO nominee (she IS it).
        WikidataDynamicObject personStmt = obj("stmt-person", "Best Actress", "Statement");
        personStmt.put("category", bestActress);
        personStmt.put("forWork", film);
        personStmt.put("year", 2004);
        watts.put("nominations", List.of(personStmt));

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(film, watts));

        ReifyConstruct reify = new ReifyConstruct(
                "Oscarnominations", "nominations", "Oscar", "source", "value", true,
                List.of(
                        new ReifyConstruct.Role("nominee", "nominee", true),
                        new ReifyConstruct.Role("work", "forWork", true)),
                List.of("nominee", "category", "work", "year"));

        List<WikidataDynamicObject> created =
                new TransformEngine().applyReify(pool, reify);

        // Two denormalized statements → ONE canonical Oscar event.
        assertEquals(1, created.size(), "film+person sides should dedup to one");
        WikidataDynamicObject oscar = created.get(0);
        assertSame(watts, oscar.get("nominee"), "nominee = person (qualifier ∨ subject)");
        assertSame(film, oscar.get("work"), "work = film (forWork ∨ subject)");
        assertSame(bestActress, oscar.get("category"));
        assertEquals(2004, oscar.get("year"));
        assertEquals("Oscar", oscar.typeName());

        // The dropped duplicate must be DEMOTED, not merely absent from the return
        // list: it's still in the pool + stamped in place, so unless it's unstamped
        // it re-surfaces as a second served Oscar. Exactly one statement stays
        // "Oscar"; the other reverts to the anonymous sentinel.
        long served = pool.stream()
                .filter(o -> "Oscar".equals(o.typeName())).count();
        assertEquals(1, served, "only the kept event is served as Oscar");
        long demoted = List.of(filmStmt, personStmt).stream()
                .filter(o -> "WikidataDynamicObject".equals(o.typeName())).count();
        assertEquals(1, demoted, "the dropped duplicate is un-stamped");
    }

    @Test void droppedDuplicatesAreCollectedAndUnlinkedFromSourceList() {
        WikidataDynamicObject film = obj("Q214013", "21 Grams", "Oscarnominations");
        WikidataDynamicObject watts = obj("Q132616", "Naomi Watts", "Oscarnominations");
        WikidataDynamicObject bestActress = obj("Q103618", "Best Actress", "Award");

        WikidataDynamicObject filmStmt = obj("stmt-film", "Best Actress", "Statement");
        filmStmt.put("category", bestActress);
        filmStmt.put("nominee", watts);
        filmStmt.put("year", 2004);
        film.put("nominations", new ArrayList<>(List.of(filmStmt)));

        // The nominee's own back-reference copy (source = the person).
        WikidataDynamicObject personStmt = obj("stmt-person", "Best Actress", "Statement");
        personStmt.put("category", bestActress);
        personStmt.put("forWork", film);
        personStmt.put("year", 2004);
        watts.put("nominations", new ArrayList<>(List.of(personStmt)));

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(film, watts));
        ReifyConstruct reify = new ReifyConstruct(
                "Oscarnominations", "nominations", "Oscar", "source", "value", true,
                List.of(new ReifyConstruct.Role("nominee", "nominee", true),
                        new ReifyConstruct.Role("work", "forWork", true)),
                List.of("nominee", "category", "work", "year"));

        TransformEngine engine = new TransformEngine();
        List<WikidataDynamicObject> created = engine.applyReify(pool, reify);

        assertEquals(1, created.size(), "collapses to one");
        assertEquals(1, engine.demoted().size(), "the person-side copy is demoted");

        // The demoted stub is UNLINKED from its source's list field, so it can't be
        // served/serialized as a duplicate untyped card.
        WikidataDynamicObject dropped = engine.demoted().iterator().next();
        assertSame(personStmt, dropped);
        Object wattsNoms = watts.get("nominations");
        assertTrue(wattsNoms instanceof java.util.Collection<?>);
        assertTrue(!((java.util.Collection<?>) wattsNoms).contains(dropped),
                "dropped stub unlinked from Naomi Watts' nomination list");
    }

    @Test void workCategoryNominationKeepsFilmAsNominee() {
        // Best Picture: the film is the nominee (no nominee qualifier).
        WikidataDynamicObject film = obj("Q47703", "Chicago", "Oscarnominations");
        WikidataDynamicObject bestPicture = obj("Q102427", "Best Picture", "Award");
        WikidataDynamicObject stmt = obj("stmt-bp", "Best Picture", "Statement");
        stmt.put("category", bestPicture);
        stmt.put("year", 2003);
        film.put("nominations", List.of(stmt));

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(film));
        ReifyConstruct reify = new ReifyConstruct(
                "Oscarnominations", "nominations", "Oscar", "source", "value", true,
                List.of(
                        new ReifyConstruct.Role("nominee", "nominee", true),
                        new ReifyConstruct.Role("work", "forWork", true)),
                List.of("nominee", "category", "work", "year"));

        List<WikidataDynamicObject> created =
                new TransformEngine().applyReify(pool, reify);

        assertEquals(1, created.size());
        assertSame(film, created.get(0).get("nominee"), "film is its own nominee");
        assertSame(film, created.get(0).get("work"));
    }

    // --- canonicalize-by-list: shared award vs separate nominations ---

    private static ReifyConstruct byNomineeList() {
        // nominees = the shared-award list (canonical marker); forWork = a single
        // inverse role (subject-fallback) that flags the denormalized person copy.
        return new ReifyConstruct(
                "Oscarnominations", "noms", "Oscar", "source", "value", true,
                List.of(new ReifyConstruct.Role("forWork", "forWork", true)),
                List.of(), "nominees");
    }

    @Test void sharedAwardIsOneNominationWithAllCoNominees() {
        WikidataDynamicObject film = obj("Q223299", "The Color Purple", "Oscarnominations");
        WikidataDynamicObject score = obj("Qscore", "Best Original Score", "Award");
        WikidataDynamicObject a = obj("Qa", "Quincy Jones", "Oscarnominations");
        WikidataDynamicObject b = obj("Qb", "Rod Temperton", "Oscarnominations");

        // Film-side shared statement: ONE statement, both composers as nominees.
        WikidataDynamicObject filmStmt = obj("st-score", "Best Original Score", "Statement");
        filmStmt.put("category", score);
        filmStmt.put("year", 1986);
        filmStmt.merge("nominees", a);
        filmStmt.merge("nominees", b);
        film.put("noms", List.of(filmStmt));

        // Person-side denormalized copies: forWork = film, no nominee list.
        WikidataDynamicObject aStmt = obj("st-a", "Best Original Score", "Statement");
        aStmt.put("category", score); aStmt.put("year", 1986); aStmt.put("forWork", film);
        a.put("noms", List.of(aStmt));
        WikidataDynamicObject bStmt = obj("st-b", "Best Original Score", "Statement");
        bStmt.put("category", score); bStmt.put("year", 1986); bStmt.put("forWork", film);
        b.put("noms", List.of(bStmt));

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(film, a, b));
        List<WikidataDynamicObject> created =
                new TransformEngine().applyReify(pool, byNomineeList());

        assertEquals(1, created.size(), "shared award → ONE nomination");
        Object nominees = created.get(0).get("nominees");
        assertTrue(nominees instanceof List, "co-nominees kept as a list");
        assertEquals(2, ((List<?>) nominees).size(), "both composers retained");
        assertSame(film, created.get(0).get("forWork"));
        assertEquals(1, pool.stream().filter(o -> "Oscar".equals(o.typeName())).count(),
                "the person-side copies are dropped, not served");
        assertEquals("WikidataDynamicObject", aStmt.typeName());
        assertEquals("WikidataDynamicObject", bStmt.typeName());
    }

    @Test void separateNominationsInSameCategoryStaySeparate() {
        // Two supporting-actress nominations for one film = two film-side statements.
        WikidataDynamicObject film = obj("Q223299", "The Color Purple", "Oscarnominations");
        WikidataDynamicObject cat = obj("Qssa", "Best Supporting Actress", "Award");
        WikidataDynamicObject oprah = obj("Qo", "Oprah Winfrey", "Oscarnominations");
        WikidataDynamicObject margaret = obj("Qm", "Margaret Avery", "Oscarnominations");

        WikidataDynamicObject s1 = obj("st-1", "Best Supporting Actress", "Statement");
        s1.put("category", cat); s1.put("year", 1986); s1.merge("nominees", oprah);
        WikidataDynamicObject s2 = obj("st-2", "Best Supporting Actress", "Statement");
        s2.put("category", cat); s2.put("year", 1986); s2.merge("nominees", margaret);
        film.put("noms", List.of(s1, s2));

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(film));
        List<WikidataDynamicObject> created =
                new TransformEngine().applyReify(pool, byNomineeList());

        assertEquals(2, created.size(), "two independent nominations stay separate");
    }

    @Test void personBackReferenceCollapsesIntoTheWorkNomination() {
        // Katharine Hepburn case: the nominee (person) ALSO carries a P1411
        // "nominated for" statement (pq:P1686 = the film) — a back-reference copy of
        // the film's own nomination. Both survive canonicalize-by-list (both name a
        // nominee), identical on (category, year, forWork, nominee) but with
        // different source. They must collapse to ONE, keeping the WORK-anchored copy
        // (source == forWork), not the person's self-copy.
        WikidataDynamicObject film = obj("Q736969", "The Lion in Winter", "Oscarnominations");
        WikidataDynamicObject hepburn = obj("Q56016", "Katharine Hepburn", "Oscarnominations");
        WikidataDynamicObject bestActress = obj("Q103618", "Best Actress", "Award");

        // Film side: nominee = Hepburn; no forWork (the role falls back to the film).
        WikidataDynamicObject filmStmt = obj("st-film", "Best Actress", "Statement");
        filmStmt.put("category", bestActress);
        filmStmt.merge("nominee", hepburn);
        filmStmt.put("year", 1968);
        film.put("noms", List.of(filmStmt));

        // Person side: same nominee (herself) + forWork = the film (real P1686).
        WikidataDynamicObject personStmt = obj("st-person", "Best Actress", "Statement");
        personStmt.put("category", bestActress);
        personStmt.merge("nominee", hepburn);
        personStmt.put("forWork", film);
        personStmt.put("year", 1968);
        hepburn.put("noms", List.of(personStmt));

        ReifyConstruct reify = new ReifyConstruct(
                "Oscarnominations", "noms", "Nomination", "source", "value", true,
                List.of(new ReifyConstruct.Role("forWork", "forWork", true)),
                List.of("category", "year", "forWork", "nominee"), "nominee");

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(film, hepburn));
        List<WikidataDynamicObject> created =
                new TransformEngine().applyReify(pool, reify);

        assertEquals(1, created.size(), "person back-reference collapses into the work's");
        assertSame(film, created.get(0).get("source"), "the kept copy is work-anchored");
        assertSame(film, created.get(0).get("forWork"));
        assertEquals(1, pool.stream().filter(o -> "Nomination".equals(o.typeName())).count(),
                "only the work-anchored copy is served");
    }

    @Test void workLessNominationMakesSubjectTheNominee() {
        // Honorary award to a person: no work-side statement, no forWork, no nominee
        // qualifier → the subject is the sole nominee.
        WikidataDynamicObject person = obj("Qh", "Honoree", "Oscarnominations");
        WikidataDynamicObject cat = obj("Qhon", "Honorary Award", "Award");
        WikidataDynamicObject stmt = obj("st-h", "Honorary Award", "Statement");
        stmt.put("category", cat); stmt.put("year", 1986);
        person.put("noms", List.of(stmt));

        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(person));
        List<WikidataDynamicObject> created =
                new TransformEngine().applyReify(pool, byNomineeList());

        assertEquals(1, created.size());
        assertSame(person, created.get(0).get("nominees"), "subject is the nominee");
    }

    @Test void compiledDerivationMatchesTheEditableModel() {
        // The Phase-1 contract: deriving from the compiled model must produce a
        // byte-for-byte identical Reification to deriving from the editable model.
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.rootClass(new GeneratedClassModel("OscarNominations"));
        GeneratedClassModel src = project.rootClass();
        src.instanceMapping().propertyPid("P1411");
        src.instanceMapping().additionalTypeQids().add("Q102427");   // Best Picture

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSource(new StatementClassSource("OscarNominations", "P1411"));
        nom.instanceMapping().sourceQid("Q19020");
        GeneratedFieldModel category =
                nom.addField("category", FieldType.ENTITY, FieldCardinality.SINGLE);
        category.mapping().propertyPid("P1411");                     // the ps: value field
        // Multiple allowed values in a defined order — valueQids becomes a VALUES
        // clause, so order must survive compilation (guards the Set.copyOf regress).
        category.mapping().allowedQids().add("Q30");
        category.mapping().allowedQids().add("Q20");
        category.mapping().allowedQids().add("Q10");
        nom.addField("forWork", FieldType.ENTITY, FieldCardinality.SINGLE)
                .mapping().qualifierPid("P1686");                    // scalar entity qualifier
        nom.addField("nominees", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .mapping().qualifierPid("P2453");                    // the nominee list
        nom.addField("won", FieldType.BOOLEAN, FieldCardinality.SINGLE)
                .mapping().productionKind(FieldProductionKind.COMPANION_MATCH);
        wikidata.explore.model.StatementCanonicalDefaults
                .replaceWithSuggestion(nom);
        project.addClass(nom);

        ModelStatementReifications.Reification editable =
                ModelStatementReifications.deriveOne(nom, project);

        CompiledProjectModel compiled = ProjectModelCompiler.compile(project);
        ModelStatementReifications.Reification fromCompiled =
                ModelStatementReifications.deriveOne(
                        compiled.findClass("Nomination").orElseThrow(), compiled);

        assertNotNull(editable);
        assertEquals(editable, fromCompiled,
                "compiled derivation must equal the editable-model derivation");

        // The whole derive() list (what enrich/reify iterate) matches too.
        assertEquals(
                ModelStatementReifications.derive(project),
                ModelStatementReifications.derive(compiled),
                "compiled derive() list must equal the editable one");

        // valueFilterGaps reads the source membership through the compiled
        // sourceMapping — assert it agrees with the editable path (here it flags
        // Q102427, a membership target not in the value filter).
        assertEquals(
                ModelStatementReifications.valueFilterGaps(editable, project),
                ModelStatementReifications.valueFilterGaps(fromCompiled, compiled),
                "compiled valueFilterGaps must match the editable one");
    }
}
