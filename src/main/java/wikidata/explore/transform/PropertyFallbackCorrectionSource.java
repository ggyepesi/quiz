package wikidata.explore.transform;

import quiz.curation.Correction;
import quiz.curation.CorrectionSource;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Executes a {@link FieldFallbackRule}: fills a missing field from a redundant
 * Wikidata statement on a related entity, matched by a join key. Generic — the
 * rule carries all the domain specifics, so the Oscar year fix is just
 * {@code new PropertyFallbackCorrectionSource(pool, client, FieldFallbackRule.oscarYear())}.
 *
 * <p>Emits fill {@link Correction}s (non-manual: {@link quiz.curation.Corrections}
 * only fills an absent field, never clobbers). Ambiguous joins (more than one
 * distinct value) and misses are left for curation.
 */
public final class PropertyFallbackCorrectionSource implements CorrectionSource {

    private static final String PREFIXES = """
            PREFIX wd: <http://www.wikidata.org/entity/>
            PREFIX p: <http://www.wikidata.org/prop/>
            PREFIX ps: <http://www.wikidata.org/prop/statement/>
            PREFIX pq: <http://www.wikidata.org/prop/qualifier/>
            """;

    private static final int BATCH = 150;

    private final List<WikidataDynamicObject> pool;
    private final WikidataSparqlClient client;
    private final FieldFallbackRule rule;
    private Consumer<String> log = s -> { };

    public PropertyFallbackCorrectionSource(List<WikidataDynamicObject> pool,
                                            WikidataSparqlClient client,
                                            FieldFallbackRule rule) {
        this.pool = pool;
        this.client = client;
        this.rule = rule;
    }

    public PropertyFallbackCorrectionSource log(Consumer<String> log) {
        this.log = log == null ? s -> { } : log;
        return this;
    }

    /** One instance needing {@code rule.field()}: its id, the join entity, and the
     *  related entities to consult (in {@code fallbackVia} order). */
    private record Gap(String id, String joinQid, List<String> entityQids) { }

    @Override
    public List<Correction> corrections() {
        List<Gap> gaps = collectGaps();
        log.accept(rule.className() + " missing " + rule.field() + ": " + gaps.size());
        if (gaps.isEmpty()) {
            return List.of();
        }

        Map<String, Set<String>> byPair = fetch(gaps);

        List<Correction> out = new ArrayList<>();
        int ambiguous = 0;
        int unresolved = 0;
        for (Gap g : gaps) {
            Set<String> hit = null;
            for (String entity : g.entityQids) {   // fallbackVia preference order
                if (entity == null) {
                    continue;
                }
                Set<String> h = byPair.get(pair(entity, g.joinQid));
                if (h != null && !h.isEmpty()) {
                    hit = h;
                    break;
                }
            }
            if (hit == null) {
                unresolved++;
            } else if (hit.size() > 1) {
                ambiguous++;   // e.g. won the same category in multiple years
            } else {
                out.add(new Correction(g.id, rule.field(), hit.iterator().next(), rule.origin()));
            }
        }
        log.accept("Filled " + out.size() + " " + rule.field() + "(s); ambiguous "
                + ambiguous + ", unresolved " + unresolved);
        return out;
    }

    private List<Gap> collectGaps() {
        List<Gap> gaps = new ArrayList<>();
        for (WikidataDynamicObject o : pool) {
            if (o == null || !rule.className().equals(o.typeName())) {
                continue;
            }
            if (rule.gateField() != null
                    && !Objects.equals(o.get(rule.gateField()), rule.gateValue())) {
                continue;
            }
            if (o.get(rule.field()) != null) {
                continue;   // already present — never overwrite
            }
            String joinQid = refQid(o.get(rule.joinField()));
            if (joinQid == null) {
                continue;
            }
            List<String> entityQids = new ArrayList<>();
            for (String viaField : rule.fallbackVia()) {
                entityQids.add(refQid(o.get(viaField)));
            }
            gaps.add(new Gap(o.getIdentifier(), joinQid, entityQids));
        }
        return gaps;
    }

    /** (entity, join) -> distinct extracted values, from the fallback statements. */
    private Map<String, Set<String>> fetch(List<Gap> gaps) {
        Set<String> entities = new LinkedHashSet<>();
        Set<String> joins = new LinkedHashSet<>();
        for (Gap g : gaps) {
            for (String e : g.entityQids) {
                if (e != null) {
                    entities.add(e);
                }
            }
            joins.add(g.joinQid);
        }

        String joinValues = values(joins);
        String extractExpr = switch (rule.extract()) {
            case YEAR -> "(YEAR(?val) AS ?out)";
            case LITERAL -> "(STR(?val) AS ?out)";
        };

        Map<String, Set<String>> result = new HashMap<>();
        List<String> entityList = new ArrayList<>(entities);
        for (int i = 0; i < entityList.size(); i += BATCH) {
            List<String> chunk = entityList.subList(i, Math.min(i + BATCH, entityList.size()));
            String sparql = PREFIXES + """
                    SELECT ?e ?j %s WHERE {
                      VALUES ?e { %s }
                      VALUES ?j { %s }
                      ?e p:%s ?st .
                      ?st ps:%s ?j .
                      ?st pq:%s ?val .
                    }
                    """.formatted(extractExpr, values(chunk), joinValues,
                    rule.property(), rule.property(), rule.valueQualifier());
            try {
                for (WikidataBinding b : client.query(sparql)) {
                    String e = b.qid("e");
                    String j = b.qid("j");
                    String outVal = b.value("out");
                    if (e == null || j == null || outVal == null || outVal.isBlank()) {
                        continue;
                    }
                    result.computeIfAbsent(pair(e, j), k -> new HashSet<>()).add(outVal.trim());
                }
            } catch (Exception ex) {
                log.accept("WDQS batch failed (" + chunk.size() + " entities): " + ex.getMessage());
            }
        }
        return result;
    }

    private static String values(Iterable<String> qids) {
        StringBuilder sb = new StringBuilder();
        for (String q : qids) {
            if (q != null && q.matches("Q\\d+")) {
                sb.append("wd:").append(q).append(' ');
            }
        }
        return sb.toString().trim();
    }

    private static String pair(String entity, String join) {
        return entity == null || join == null ? " " : entity + "|" + join;
    }

    /** The qid of a reference value — a single {@link WikidataDynamicObject} or the
     *  first in a collection; null for a bare string / absent. */
    private static String refQid(Object value) {
        if (value instanceof WikidataDynamicObject w) {
            return w.qid();
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof WikidataDynamicObject w) {
                    return w.qid();
                }
            }
        }
        return null;
    }
}
