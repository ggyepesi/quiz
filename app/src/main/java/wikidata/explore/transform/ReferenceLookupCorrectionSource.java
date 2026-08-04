package wikidata.explore.transform;

import wikidata.WikidataIds;

import quiz.curation.Correction;
import quiz.curation.CorrectionSource;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Executes a {@link ReferenceLookupRule}: fills a missing field by following a
 * reference field to its target entity (already in the pool) and reading a direct
 * property off it via one batched WDQS query. Generic — the Oscar edition-year
 * fix is {@code new ReferenceLookupCorrectionSource(pool, client,
 * ReferenceLookupRule.oscarEditionYear())}.
 *
 * <p>Emits fill {@link Correction}s (non-manual: fills an absent field only).
 * Targets with more than one distinct value are left for curation.
 */
public final class ReferenceLookupCorrectionSource implements CorrectionSource {

    private static final String PREFIXES = """
            PREFIX wd: <http://www.wikidata.org/entity/>
            PREFIX wdt: <http://www.wikidata.org/prop/direct/>
            """;

    private static final int BATCH = 200;

    private final List<WikidataDynamicObject> pool;
    private final WikidataSparqlClient client;
    private final ReferenceLookupRule rule;
    private Consumer<String> log = s -> { };

    public ReferenceLookupCorrectionSource(List<WikidataDynamicObject> pool,
                                           WikidataSparqlClient client,
                                           ReferenceLookupRule rule) {
        this.pool = pool;
        this.client = client;
        this.rule = rule;
    }

    public ReferenceLookupCorrectionSource log(Consumer<String> log) {
        this.log = log == null ? s -> { } : log;
        return this;
    }

    @Override
    public List<Correction> corrections() {
        Map<String, String> gaps = new LinkedHashMap<>();   // instanceId -> targetQid
        for (WikidataDynamicObject o : pool) {
            if (o == null || !rule.className().equals(o.typeName())) {
                continue;
            }
            if (o.get(rule.field()) != null) {
                continue;   // already present — never overwrite
            }
            String target = refQid(o.get(rule.viaField()));
            if (target != null) {
                gaps.put(o.getIdentifier(), target);
            }
        }
        log.accept(rule.className() + " missing " + rule.field()
                + " via " + rule.viaField() + ": " + gaps.size());
        if (gaps.isEmpty()) {
            return List.of();
        }

        Map<String, String> byTarget = fetch(new LinkedHashSet<>(gaps.values()));

        List<Correction> out = new ArrayList<>();
        int unresolved = 0;
        for (Map.Entry<String, String> e : gaps.entrySet()) {
            String value = byTarget.get(e.getValue());
            if (value == null) {
                unresolved++;
            } else {
                out.add(new Correction(e.getKey(), rule.field(), value, rule.origin()));
            }
        }
        log.accept("Filled " + out.size() + " " + rule.field()
                + "(s); unresolved " + unresolved);
        return out;
    }

    /** target qid -> its single value (ambiguous targets dropped). */
    private Map<String, String> fetch(Set<String> targets) {
        String extractExpr = switch (rule.extract()) {
            case YEAR -> "(YEAR(?v) AS ?out)";
            case LITERAL -> "(STR(?v) AS ?out)";
        };

        Map<String, Set<String>> multi = new HashMap<>();
        List<String> list = new ArrayList<>(targets);
        for (int i = 0; i < list.size(); i += BATCH) {
            List<String> chunk = list.subList(i, Math.min(i + BATCH, list.size()));
            String sparql = PREFIXES + """
                    SELECT ?e %s WHERE {
                      VALUES ?e { %s }
                      ?e wdt:%s ?v .
                    }
                    """.formatted(extractExpr, values(chunk), rule.property());
            try {
                for (WikidataBinding b : client.query(sparql)) {
                    String e = b.qid("e");
                    String out = b.value("out");
                    if (e != null && out != null && !out.isBlank()) {
                        multi.computeIfAbsent(e, k -> new HashSet<>()).add(out.trim());
                    }
                }
            } catch (Exception ex) {
                log.accept("WDQS batch failed (" + chunk.size() + " targets): " + ex.getMessage());
            }
        }

        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : multi.entrySet()) {
            if (e.getValue().size() == 1) {
                result.put(e.getKey(), e.getValue().iterator().next());
            }
        }
        return result;
    }

    private static String values(Iterable<String> qids) {
        StringBuilder sb = new StringBuilder();
        for (String q : qids) {
            if (q != null && WikidataIds.isQid(q)) {
                sb.append("wd:").append(q).append(' ');
            }
        }
        return sb.toString().trim();
    }

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
