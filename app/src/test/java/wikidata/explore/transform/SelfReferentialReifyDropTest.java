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
        List<WikidataDynamicObject> result = engine.applyReify(pool, nominationReify());

        assertFalse(result.contains(filmPhantom),
                "the film's witnessed self-referential phantom is dropped");
        assertTrue(engine.demoted().contains(filmPhantom),
                "the phantom is demoted (un-stamped + unlinked)");
        assertTrue(result.contains(filmBestPicture),
                "the film's legitimate Best Picture self-nomination (no witness) is kept");
        assertTrue(result.contains(personReal),
                "the genuine person-rooted nomination is kept");
    }
}
