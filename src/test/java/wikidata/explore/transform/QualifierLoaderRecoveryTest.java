package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class QualifierLoaderRecoveryTest {

    private static WikidataDynamicObject obj(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    // Simulates a flaky endpoint: when both entities are in one batch the query
    // silently returns only Q11 (Q22 dropped — a partial 200); Q22 queried ALONE
    // (the recovery pass) succeeds.
    private static final class FlakyClient extends WikidataSparqlClient {
        FlakyClient() {
            super("test");
        }

        @Override
        public List<WikidataBinding> query(String sparql) {
            boolean hasE1 = sparql.contains("wd:Q11");
            boolean hasE2 = sparql.contains("wd:Q22");
            List<WikidataBinding> rows = new ArrayList<>();
            if (hasE1) {
                rows.add(row("Q11"));
            }
            if (hasE2 && !hasE1) {          // Q22 only comes back when isolated
                rows.add(row("Q22"));
            }
            return rows;
        }

        private static WikidataBinding row(String e) {
            Map<String, String> v = new HashMap<>();
            v.put("e", "http://www.wikidata.org/entity/" + e);
            v.put("st", e + "-GUID");
            v.put("value", "http://www.wikidata.org/entity/Q500");
            v.put("valueLabel", "Best Picture");
            return new WikidataBinding(v);
        }
    }

    @Test void recoversEntitiesDroppedByAFlakyBatch() {
        WikidataDynamicObject e1 = obj("Q11", "A", "OscarNominations");
        WikidataDynamicObject e2 = obj("Q22", "B", "OscarNominations");

        // valueQids set (the category) → entity-anchored path, which has the
        // "every pool entity must get a statement" guarantee the recovery relies on.
        QualifierLoadConfig cfg = new QualifierLoadConfig(
                "OscarNominations", "P1411", "__Nomination", "Nomination",
                "category", "", List.of(), List.of("Q500"));

        new QualifierLoader().enrich(List.of(e1, e2), cfg, new FlakyClient(), null);

        assertNotNull(e1.get("__Nomination"), "Q11 loaded in the main batch");
        assertNotNull(e2.get("__Nomination"),
                "Q22 recovered after being silently dropped from the combined batch");
    }

    // The actual Oscars case: no valueQids, only a value TYPE → VALUE-anchored main
    // pass (batched by category). The recovery must still run and re-query the
    // dropped entity ENTITY-anchored.
    private static final class FlakyValueAnchoredClient extends WikidataSparqlClient {
        FlakyValueAnchoredClient() {
            super("test");
        }

        @Override
        public List<WikidataBinding> query(String sparql) {
            List<WikidataBinding> rows = new ArrayList<>();
            if (!sparql.contains("p:P1411")) {          // fetchValueQids: the category set
                Map<String, String> v = new HashMap<>();
                v.put("value", "http://www.wikidata.org/entity/Q500");
                rows.add(new WikidataBinding(v));
                return rows;
            }
            if (sparql.contains("wd:Q22")) {            // entity-anchored recovery for Q22
                rows.add(FlakyClient.row("Q22"));
            } else {                                    // value-anchored main pass drops Q22
                rows.add(FlakyClient.row("Q11"));
            }
            return rows;
        }
    }

    // Many categories → many value-anchored batches that run concurrently, all
    // attaching statements to the SAME nominee (one entity nominated in every
    // category). Without the serial-apply lock, concurrent merges onto that one
    // entity's statement list would lose or corrupt statements; with it, every
    // batch's statement lands exactly once.
    private static final class ManyCategoryClient extends WikidataSparqlClient {
        private final List<String> categories;

        ManyCategoryClient(List<String> categories) {
            super("test");
            this.categories = categories;
        }

        @Override
        public List<WikidataBinding> query(String sparql) {
            List<WikidataBinding> rows = new ArrayList<>();
            if (!sparql.contains("p:P1411")) {          // fetchValueQids: the category set
                for (String cat : categories) {
                    Map<String, String> v = new HashMap<>();
                    v.put("value", "http://www.wikidata.org/entity/" + cat);
                    rows.add(new WikidataBinding(v));
                }
                return rows;
            }
            // Value-anchored main pass: the one nominee (Q7) has a statement in every
            // category pinned in this batch — a distinct statement GUID per category.
            for (String cat : categories) {
                if (sparql.contains("wd:" + cat)) {
                    Map<String, String> v = new HashMap<>();
                    v.put("e", "http://www.wikidata.org/entity/Q7");
                    v.put("st", "GUID-" + cat);
                    v.put("value", "http://www.wikidata.org/entity/" + cat);
                    v.put("valueLabel", cat);
                    rows.add(new WikidataBinding(v));
                }
            }
            return rows;
        }
    }

    @Test void concurrentValueBatchesAttachEveryStatementToTheSharedEntity() {
        WikidataDynamicObject nominee = obj("Q7", "Prolific Nominee", "OscarNominations");

        List<String> categories = new ArrayList<>();
        for (int i = 0; i < 64; i++) {          // 64 / VALUE_BATCH(8) = 8 concurrent batches
            categories.add("Q" + (3000 + i));
        }

        QualifierLoadConfig cfg = new QualifierLoadConfig(
                "OscarNominations", "P1411", "__Nomination", "Nomination",
                "category", "Q19020", List.of());

        List<WikidataDynamicObject> created = new QualifierLoader()
                .enrich(List.of(nominee), cfg, new ManyCategoryClient(categories), null);

        assertEquals(64, created.size(),
                "every category's statement is created once — none lost to a race");
        Object statements = nominee.get("__Nomination");
        assertNotNull(statements, "the shared nominee carries its statements");
        assertEquals(64, ((Collection<?>) statements).size(),
                "all 64 statements attached to the one entity, no lost updates");
    }

    @Test void recoversInTheValueAnchoredPathToo() {
        WikidataDynamicObject e1 = obj("Q11", "A", "OscarNominations");
        WikidataDynamicObject e2 = obj("Q22", "B", "OscarNominations");

        // valueTypeQid set, no valueQids → value-anchored (the Oscars config).
        QualifierLoadConfig cfg = new QualifierLoadConfig(
                "OscarNominations", "P1411", "__Nomination", "Nomination",
                "category", "Q19020", List.of());

        new QualifierLoader().enrich(List.of(e1, e2), cfg, new FlakyValueAnchoredClient(), null);

        assertNotNull(e1.get("__Nomination"), "Q11 loaded in the value-anchored pass");
        assertNotNull(e2.get("__Nomination"),
                "Q22 recovered entity-anchored even though the main pass was value-anchored");
    }
}
