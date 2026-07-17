package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompanionMatchTest {

    /** Client that answers the (value-anchored) load with canned win rows. */
    private static final class StubClient extends WikidataSparqlClient {
        private final List<WikidataBinding> rows;
        StubClient(List<WikidataBinding> rows) { super("companion-match-test"); this.rows = rows; }
        @Override public List<WikidataBinding> query(String sparql) { return rows; }
    }

    private static WikidataBinding row(String subj, String value, String role) {
        return role == null
                ? new WikidataBinding(Map.of("subj", subj, "value", value))
                : new WikidataBinding(Map.of("subj", subj, "value", value, "role", role));
    }

    // won = COMPANION_MATCH on Nomination: subject=nominee, P166 [ps=category,
    // pq:P1686=for-work], value=category, role=source.
    private static GeneratedProjectModel projectWithWon() {
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        GeneratedFieldModel won = new GeneratedFieldModel(
                "won", FieldType.BOOLEAN, FieldCardinality.SINGLE);
        won.mapping().productionKind(FieldProductionKind.COMPANION_MATCH);
        won.mapping().propertyPid("P166");
        won.mapping().qualifierPid("P1686");
        won.mapping().subjectField("nominee");
        won.mapping().matchValueField("category");
        won.mapping().matchRoleField("source");
        nomination.fields().add(won);

        GeneratedProjectModel p = new GeneratedProjectModel();
        p.rootClass(nomination);
        return p;
    }

    private static WikidataDynamicObject nomination(
            String qid, String film, String category, String nominee) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, qid);
        o.type("Nomination");
        o.put("source", entity(film));
        o.put("category", entity(category));
        o.put("nominee", entity(nominee));
        return o;
    }

    private static WikidataDynamicObject entity(String qid) {
        return new WikidataDynamicObject(qid, qid);
    }

    @Test void marksActingWinRecordedOnTheNomineeWithForWork() {
        // Win on the person: nominee Q41163 (Al Pacino), Best Actor Q103916,
        // for-work Q426517 (the film) — matches nominee's nomination for that film.
        WikidataDynamicObject winner =
                nomination("Q426517__x", "Q426517", "Q103916", "Q41163");
        // Same category+film, different nominee — a co-nominee who lost.
        WikidataDynamicObject loser =
                nomination("Q426517__y", "Q426517", "Q103916", "Q999");
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(winner, loser));

        CompanionMatch.apply(projectWithWon(), pool,
                new StubClient(List.of(row("Q41163", "Q103916", "Q426517"))), null);

        assertEquals(Boolean.TRUE, winner.get("won"));
        assertEquals(Boolean.FALSE, loser.get("won"));
    }

    @Test void winWithoutForWorkKeysBackToTheSubject() {
        // Best Picture: win recorded on the film itself, no P1686. nominee=film,
        // source=film; the loader COALESCEs role to the subject (the film).
        WikidataDynamicObject bestPicture =
                nomination("Q222__x", "Q222", "Q102427", "Q222");
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(bestPicture));

        CompanionMatch.apply(projectWithWon(), pool,
                new StubClient(List.of(row("Q222", "Q102427", null))), null);

        assertEquals(Boolean.TRUE, bestPicture.get("won"));
    }
}
