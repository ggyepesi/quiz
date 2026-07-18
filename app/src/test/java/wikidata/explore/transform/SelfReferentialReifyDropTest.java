package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #99 Phase 2: a fully self-referential reified atom (every role field fell back
 * to the subject) is dropped ONLY when a witness exists — another atom, in the
 * same category, whose real qualifier references this atom's subject. Without a
 * same-category witness a self-nomination is legitimate (a film IS its Best
 * Picture nominee) and is kept. Generic — driven only by recorded field origins.
 */
class SelfReferentialReifyDropTest {

    private static WikidataDynamicObject obj(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    private static ReifyConstruct nominationReify() {
        // Legacy subject-fallback ON for every role (the config that makes phantoms).
        return new ReifyConstruct(
                "Oscarnominations", "nominations", "Nomination", "source", "value", true,
                List.of(new ReifyConstruct.Role("nominee", "nominee", true),
                        new ReifyConstruct.Role("forWork", "forWork", true),
                        new ReifyConstruct.Role("edition", "edition", true)),
                List.of());   // no dedup — isolate the witness filter
    }

    @Test
    void witnessedPhantomDroppedButLegitimateSelfNominationKept() {
        WikidataDynamicObject work = obj("Qwork", "The Whale", "Oscarnominations");
        WikidataDynamicObject bestSupporting = obj("Qbsa", "Best Supporting Actress", "Award");
        WikidataDynamicObject bestPicture = obj("Qbp", "Best Picture", "Award");

        // Film's BARE Best Supporting Actress statement — no qualifiers. That award
        // really belongs to a person (below), so this is a phantom self-copy.
        WikidataDynamicObject filmPhantom = obj("st-film-bsa", "x", "Statement");
        filmPhantom.put("category", bestSupporting);

        // Film's LEGITIMATE Best Picture statement — the film is genuinely the
        // nominee, and no person holds a "Best Picture, forWork=this film" record.
        WikidataDynamicObject filmBestPicture = obj("st-film-bp", "x", "Statement");
        filmBestPicture.put("category", bestPicture);

        work.put("nominations", List.of(filmPhantom, filmBestPicture));

        // The genuine person-rooted Best Supporting Actress record: forWork = the
        // film (a REAL qualifier). This witnesses the film's phantom.
        WikidataDynamicObject person = obj("Qperson", "Hong Chau", "Oscarnominations");
        WikidataDynamicObject personReal = obj("st-person", "x", "Statement");
        personReal.put("category", bestSupporting);
        personReal.put("forWork", work);
        person.put("nominations", List.of(personReal));

        TransformEngine engine = new TransformEngine();
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(
                work, person, filmPhantom, filmBestPicture, personReal,
                bestSupporting, bestPicture));
        List<WikidataDynamicObject> result =
                engine.applyReify(pool, nominationReify(), "category");

        assertFalse(result.contains(filmPhantom),
                "the film's witnessed self-referential phantom is dropped");
        assertTrue(engine.demoted().contains(filmPhantom),
                "the phantom is demoted (un-stamped + unlinked)");
        assertTrue(result.contains(filmBestPicture),
                "the film's legitimate Best Picture self-nomination (no witness) is kept");
        assertTrue(result.contains(personReal),
                "the genuine person-rooted nomination is kept");
    }

    @Test
    void incidentalQualifierOnPhantomDoesNotBlockTheWitness() {
        // The witness match keys on the event SLOT (category), not on every loaded
        // field. A phantom that carries an incidental qualifier the witness lacks
        // (here a "song") must still be recognized as the witness's self-copy.
        WikidataDynamicObject work = obj("Qwork", "A Film", "Oscarnominations");
        WikidataDynamicObject cat = obj("Qbos", "Best Original Song", "Award");

        WikidataDynamicObject filmPhantom = obj("st-film", "x", "Statement");
        filmPhantom.put("category", cat);
        filmPhantom.put("song", obj("Qsong", "Some Song", "Work")); // incidental, witness-less
        work.put("nominations", List.of(filmPhantom));

        WikidataDynamicObject person = obj("Qperson", "A Songwriter", "Oscarnominations");
        WikidataDynamicObject personReal = obj("st-person", "x", "Statement");
        personReal.put("category", cat);
        personReal.put("forWork", work);   // REFERENCEs the film → witnesses its phantom
        person.put("nominations", List.of(personReal));

        TransformEngine engine = new TransformEngine();
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(
                work, person, filmPhantom, personReal, cat));
        List<WikidataDynamicObject> result =
                engine.applyReify(pool, nominationReify(), "category");

        assertFalse(result.contains(filmPhantom),
                "the phantom is dropped despite carrying an incidental 'song' the witness lacks");
        assertTrue(result.contains(personReal), "the genuine record is kept");
    }

    @Test
    void applyAggregatesDemotionsAcrossMultipleReifies() {
        // apply() runs each reify through applyReify, which resets its per-call audit
        // state. The aggregate on the engine must still reflect BOTH reifies, not just
        // the last one, or earlier demotions/findings are silently lost.
        TransformConfig config = new TransformConfig();
        List<WikidataDynamicObject> pool = new ArrayList<>();

        // Two independent statement classes, each with a witnessed phantom.
        for (String tag : List.of("A", "B")) {
            WikidataDynamicObject work = obj("Qwork" + tag, "Work " + tag, "Src" + tag);
            WikidataDynamicObject cat = obj("Qcat" + tag, "Category " + tag, "Award");
            WikidataDynamicObject phantom = obj("st-bare" + tag, "x", "Statement");
            phantom.put("category", cat);
            work.put("nominations", List.of(phantom));
            WikidataDynamicObject person = obj("Qp" + tag, "Person " + tag, "Src" + tag);
            WikidataDynamicObject real = obj("st-real" + tag, "x", "Statement");
            real.put("category", cat);
            real.put("forWork", work);
            person.put("nominations", List.of(real));
            pool.addAll(List.of(work, person, phantom, real, cat));

            config.reifies.add(new ReifyConstruct(
                    "Src" + tag, "nominations", "Nomination", "source", "value", true,
                    List.of(new ReifyConstruct.Role("nominee", "nominee", true),
                            new ReifyConstruct.Role("forWork", "forWork", true)),
                    List.of()));
        }

        TransformEngine engine = new TransformEngine();
        engine.apply(pool, config);

        assertTrue(engine.demoted().size() >= 2,
                "both reifies' demoted phantoms survive on the engine, not just the last");
        assertTrue(engine.selfReferenceFindings().size() >= 2,
                "both reifies' findings survive on the engine, not just the last");
    }

    @Test
    void applyKeysWitnessOnTheSlotViaThePairedLoadValueField() {
        // apply() joins each reify to its QualifierLoadConfig (listField ==
        // statementField) to recover the reified value field, so the witness match
        // keys on the event slot even through the whole-Transform path. Here the
        // phantom carries an incidental "song" the witness lacks — dropped only if the
        // slot context (category), not the broad field set, is used.
        WikidataDynamicObject work = obj("Qwork", "A Film", "Src");
        WikidataDynamicObject cat = obj("Qcat", "Best Original Song", "Award");
        WikidataDynamicObject phantom = obj("st-film", "x", "Statement");
        phantom.put("category", cat);
        phantom.put("song", obj("Qsong", "Some Song", "Work"));
        work.put("nominations", List.of(phantom));
        WikidataDynamicObject person = obj("Qp", "A Songwriter", "Src");
        WikidataDynamicObject real = obj("st-person", "x", "Statement");
        real.put("category", cat);
        real.put("forWork", work);
        person.put("nominations", List.of(real));

        TransformConfig config = new TransformConfig();
        // The Load names the value field (category); its statementField matches the
        // reify's listField, which is how apply() recovers it.
        config.qualifierLoads.add(new QualifierLoadConfig(
                "Src", "P1411", "nominations", "Statement", "category", "",
                List.of()));
        config.reifies.add(new ReifyConstruct(
                "Src", "nominations", "Nomination", "source", "value", true,
                List.of(new ReifyConstruct.Role("nominee", "nominee", true),
                        new ReifyConstruct.Role("forWork", "forWork", true)),
                List.of()));

        TransformEngine engine = new TransformEngine();
        List<WikidataDynamicObject> pool =
                new ArrayList<>(List.of(work, person, phantom, real, cat));
        engine.apply(pool, config);   // client==null: the Load isn't run, but its
                                      // valueField is still joined to the reify

        assertTrue(engine.demoted().contains(phantom),
                "apply() drops the phantom via the paired Load's category value field, "
                        + "despite the incidental 'song' the witness lacks");
    }

    @Test
    void reusingEngineDoesNotLeakDemotionsOrFindings() {
        WikidataDynamicObject work = obj("Qwork", "The Whale", "Oscarnominations");
        WikidataDynamicObject cat = obj("Qbsa", "Best Supporting Actress", "Award");
        WikidataDynamicObject phantom = obj("st-bare", "x", "Statement");
        phantom.put("category", cat);
        work.put("nominations", List.of(phantom));
        WikidataDynamicObject person = obj("Qperson", "Hong Chau", "Oscarnominations");
        WikidataDynamicObject real = obj("st-real", "x", "Statement");
        real.put("category", cat);
        real.put("forWork", work);
        person.put("nominations", List.of(real));

        TransformEngine engine = new TransformEngine();

        // First call drops the phantom, recording a demotion + a finding.
        engine.applyReify(new ArrayList<>(List.of(work, person, phantom, real)),
                nominationReify(), "category");
        assertFalse(engine.demoted().isEmpty(), "first call demotes the phantom");
        assertFalse(engine.selfReferenceFindings().isEmpty(), "first call has findings");

        // Reusing the engine on a clean pool must NOT carry the first call's state.
        engine.applyReify(new ArrayList<>(), nominationReify(), "category");
        assertTrue(engine.demoted().isEmpty(),
                "a reused engine's demotions reset per applyReify call");
        assertTrue(engine.selfReferenceFindings().isEmpty(),
                "a reused engine's findings reset per applyReify call");
    }
}
