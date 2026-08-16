package wikidata.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deterministic in-memory stand-in for {@link WikidataApiClient}, so extraction,
 * qualifier-load and reify can be exercised end-to-end in tests without hitting the
 * live action API. Populate it with entities and statements (recorded from real
 * Wikidata data, e.g. via {@code wbgetentities}), then inject it via
 * {@code RuleTreeExtractor.api(...)} / {@code QualifierLoader.api(...)}.
 *
 * <pre>
 *   FakeWikidataApiClient api = new FakeWikidataApiClient()
 *       .entity("Q105883400", "The Whale")
 *       .statement("Q105883400", "P1411", "Q105883400$s1", "Q106301", Map.of())        // bare
 *       .statement("Q38195662", "P1411", "Q38195662$s1", "Q106301",                    // real
 *               Map.of("P1686", List.of("Q105883400"), "P805", List.of("Q66707607")));
 * </pre>
 */
public class FakeWikidataApiClient extends WikidataApiClient {

    private final Map<String, ApiEntity> entities = new LinkedHashMap<>();
    // subjectQid -> statementPid -> statements
    private final Map<String, Map<String, List<ApiStatement>>> statements =
            new LinkedHashMap<>();

    public FakeWikidataApiClient() {
        super("FakeWikidataApiClient/1.0 (test)");
    }

    /** Register an entity's label and its outgoing entity-claim QIDs (e.g. P31). */
    public FakeWikidataApiClient entity(String qid, String label,
                                        Map<String, List<String>> claimEntityQids) {
        entities.put(qid, new ApiEntity(qid, label,
                claimEntityQids == null ? Map.of() : claimEntityQids));
        return this;
    }

    public FakeWikidataApiClient entity(String qid, String label) {
        return entity(qid, label, Map.of());
    }

    /** Register one statement: {@code subject P<pid> value [qualifiers]}. */
    public FakeWikidataApiClient statement(String subjectQid, String pid, String stmtId,
                                           String value,
                                           Map<String, List<String>> qualifiers) {
        statements.computeIfAbsent(subjectQid, k -> new LinkedHashMap<>())
                  .computeIfAbsent(pid, k -> new ArrayList<>())
                  .add(new ApiStatement(stmtId, value,
                          qualifiers == null ? Map.of() : qualifiers));
        return this;
    }

    @Override
    public Map<String, ApiEntity> getEntities(
            List<String> qids, List<String> claimPids, BatchLog batchLog) {
        Map<String, ApiEntity> out = new LinkedHashMap<>();
        if (qids == null) {
            return out;
        }
        for (String qid : qids) {
            ApiEntity e = entities.get(qid);
            if (e != null) {
                out.put(qid, e);
            }
        }
        return out;
    }

    @Override
    public PartialEntities getEntityClaimsPartial(
            List<String> qids, List<String> claimPids, BatchLog batchLog) {
        return new PartialEntities(getEntities(qids, claimPids, batchLog), 0);
    }

    @Override
    public PartialEntities getEntitiesBestEffort(
            List<String> qids, List<String> claimPids, BatchLog batchLog) {
        return new PartialEntities(getEntities(qids, claimPids, batchLog), 0);
    }

    @Override
    public Map<String, List<ApiStatement>> getStatements(
            List<String> entityQids, String statementPid,
            List<String> qualifierPids, BatchLog batchLog) {
        Map<String, List<ApiStatement>> out = new LinkedHashMap<>();
        if (entityQids == null || statementPid == null) {
            return out;
        }
        for (String qid : entityQids) {
            List<ApiStatement> sts =
                    statements.getOrDefault(qid, Map.of()).get(statementPid);
            if (sts != null && !sts.isEmpty()) {
                out.put(qid, new ArrayList<>(sts));
            }
        }
        return out;
    }

    @Override
    public Map<String, Map<String, List<ApiStatement>>> getStatementsByProperty(
            List<String> entityQids, List<String> statementPids, BatchLog batchLog) {
        Map<String, Map<String, List<ApiStatement>>> out = new LinkedHashMap<>();
        if (statementPids == null) return out;
        for (String pid : statementPids) {
            out.put(pid, getStatements(entityQids, pid, List.of(), batchLog));
        }
        return out;
    }

    /** Every entity this fake knows is reachable, so nothing is unavailable. */
    @Override
    public PartialStatements getStatementsByPropertyPartial(
            List<String> entityQids, List<String> statementPids, BatchLog batchLog) {
        return new PartialStatements(
                getStatementsByProperty(entityQids, statementPids, batchLog), 0, List.of());
    }
}
