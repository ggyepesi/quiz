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

    /** Client that answers the companion load with one canned win row. */
    private static final class StubClient extends WikidataSparqlClient {
        StubClient() { super("companion-match-test"); }
        @Override public List<WikidataBinding> query(String sparql) {
            // Scent of a Woman won Best Actor for Al Pacino.
            return List.of(new WikidataBinding(Map.of(
                    "subj", "Q321561", "value", "Q103916", "role", "Q41163")));
        }
    }

    private static GeneratedProjectModel projectWithWon() {
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        GeneratedFieldModel won = new GeneratedFieldModel(
                "won", FieldType.BOOLEAN, FieldCardinality.SINGLE);
        won.mapping().productionKind(FieldProductionKind.COMPANION_MATCH);
        won.mapping().propertyPid("P166");      // companion property (win)
        won.mapping().qualifierPid("P1346");     // role qualifier (winner)
        won.mapping().matchValueField("category");
        won.mapping().matchRoleField("nominee");
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
        WikidataDynamicObject o = new WikidataDynamicObject(qid, qid);
        return o;
    }

    @Test void marksTheWinningNominationAndLeavesTheLoserFalse() {
        // Al Pacino / Scent of a Woman / Best Actor — the win in the stub.
        WikidataDynamicObject winner =
                nomination("Q321561__x", "Q321561", "Q103916", "Q41163");
        // Same film+category, different nominee — a co-nominee who lost.
        WikidataDynamicObject loser =
                nomination("Q321561__y", "Q321561", "Q103916", "Q999");
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(winner, loser));

        CompanionMatch.apply(projectWithWon(), pool, new StubClient(), null);

        assertEquals(Boolean.TRUE, winner.get("won"));
        assertEquals(Boolean.FALSE, loser.get("won"));
    }
}
