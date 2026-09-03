package wikidata.explore.transform;

import wikidata.explore.model.EntityBound;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.RoleKind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The surviving-phantom probe (#99): a fully self-referential atom whose subject
 * is referenced through a REFERENCE role (forWork) by a real atom in the same
 * slot (category) is a witness the drop missed. This is the explicit audit that
 * replaces "notice a weird member card".
 */
class ConsistencyReportTest {

    private static WikidataDynamicObject nom(String qid, String name,
            WikidataDynamicObject source, WikidataDynamicObject nominee,
            WikidataDynamicObject forWork, WikidataDynamicObject category) {
        WikidataDynamicObject n = new WikidataDynamicObject(qid, name);
        n.type("Nomination");
        n.put("source", source);
        n.put("nominee", nominee);
        n.put("forWork", forWork);
        n.put("category", category);
        return n;
    }

    private static WikidataDynamicObject ent(String qid, String name) {
        return new WikidataDynamicObject(qid, name);
    }

    // nominee = IDENTITY, forWork = REFERENCE, value(category) drives the slot.
    private static ModelStatementReifications.Reification reify() {
        QualifierLoadConfig load = new QualifierLoadConfig(
                "OscarNominations",
                "P1411",
                "__Nomination",
                "Nomination",
                "category",
                EntityBound.unbounded(),
                List.of());
        ReifyConstruct rc = new ReifyConstruct(
                "OscarNominations", "__Nomination", "Nomination", "source", "value", true,
                List.of(new ReifyConstruct.Role("nominee", "nominee", true, RoleKind.IDENTITY),
                        new ReifyConstruct.Role("forWork", "forWork", true, RoleKind.REFERENCE)),
                List.of());
        return new ModelStatementReifications.Reification(load, rc);
    }

    @Test void survivingPhantomIsFlagged() {
        WikidataDynamicObject film = ent("Qfilm", "The Whale");
        WikidataDynamicObject person = ent("Qperson", "Hong Chau");
        WikidataDynamicObject cat = ent("Qbsa", "Best Supporting Actress");

        // The film's fully self-referential atom (nominee=forWork=source=film) —
        // a phantom that survived the drop.
        WikidataDynamicObject phantom = nom("Qfilm$p", "The Whale — BSA",
                film, film, film, cat);
        // The genuine person atom that references the film via forWork (a witness).
        WikidataDynamicObject real = nom("Qperson$r", "Hong Chau — BSA",
                person, person, film, cat);

        int suspects = ConsistencyReport.checkReify(
                reify(), List.of(phantom, real), null);
        assertEquals(1, suspects, "the film's self-ref, witnessed via forWork, is flagged");
    }

    @Test void legitimateSelfNominationInAnotherSlotIsNotFlagged() {
        WikidataDynamicObject film = ent("Qfilm", "A Film");
        WikidataDynamicObject person = ent("Qperson", "A Person");
        WikidataDynamicObject bestPicture = ent("Qbp", "Best Picture");
        WikidataDynamicObject bestActor = ent("Qba", "Best Actor");

        // Film IS its Best-Picture nominee (self-ref) — no forWork witness in that slot.
        WikidataDynamicObject selfNom = nom("Qfilm$bp", "A Film — Best Picture",
                film, film, film, bestPicture);
        // A person's real atom references the film via forWork, but in a DIFFERENT slot.
        WikidataDynamicObject other = nom("Qperson$ba", "A Person — Best Actor",
                person, person, film, bestActor);

        int suspects = ConsistencyReport.checkReify(
                reify(), List.of(selfNom, other), null);
        assertEquals(0, suspects, "a self-nomination with no same-slot witness is kept");
    }
}
