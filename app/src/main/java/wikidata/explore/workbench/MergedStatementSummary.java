package wikidata.explore.workbench;

import wikidata.WikidataSparqlClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coverage view across several sample entities — the answer to "one example
 * misleads." Merges N {@link EntityStatementSummary}s and reports, per property,
 * on how many samples it appears and, per qualifier, on how many of that
 * property's statements it's present. That last number is the reliability
 * signal: it's what would have shown `point in time` on ~44/45 (the ~3% gap
 * behind the Oscar year detour) at a glance, versus a single entity where it
 * looks total.
 */
public final class MergedStatementSummary {

    public record QualifierCoverage(String pid, String label, int statementsWith) { }

    public record PropertyCoverage(
            String pid, String label,
            int entitiesWith, int totalStatements,
            List<QualifierCoverage> qualifiers) { }

    private final int sampleCount;
    private final List<PropertyCoverage> properties;

    private MergedStatementSummary(int sampleCount, List<PropertyCoverage> properties) {
        this.sampleCount = sampleCount;
        this.properties = properties;
    }

    public int sampleCount() { return sampleCount; }

    public List<PropertyCoverage> properties() { return properties; }

    /** Fetch each QID and merge. Failed fetches are skipped (they'd bias coverage). */
    public static MergedStatementSummary fetch(List<String> qids, WikidataSparqlClient client) {
        List<EntityStatementSummary> ok = new ArrayList<>();
        for (String qid : qids) {
            try {
                ok.add(EntityStatementSummary.fetch(qid, client));
            } catch (Exception ignored) {
                // skip — a failed sample would understate coverage
            }
        }
        return of(ok);
    }

    public static MergedStatementSummary of(List<EntityStatementSummary> summaries) {
        Map<String, Agg> byPid = new LinkedHashMap<>();
        for (EntityStatementSummary s : summaries) {
            for (EntityStatementSummary.Property p : s.properties()) {
                Agg a = byPid.computeIfAbsent(p.pid(), k -> new Agg(p.pid(), p.label()));
                a.entitiesWith++;                       // one Property per pid per entity
                for (EntityStatementSummary.Statement st : p.statements()) {
                    a.totalStatements++;
                    // count a qualifier once per statement (a statement may repeat it)
                    for (String qpid : distinctQualifierPids(st)) {
                        a.quals.computeIfAbsent(qpid,
                                k -> new QAgg(qpid, qualifierLabel(st, qpid))).statementsWith++;
                    }
                }
            }
        }

        List<PropertyCoverage> props = new ArrayList<>();
        for (Agg a : byPid.values()) {
            List<QualifierCoverage> quals = new ArrayList<>();
            for (QAgg qa : a.quals.values()) {
                quals.add(new QualifierCoverage(qa.pid, qa.label, qa.statementsWith));
            }
            quals.sort(Comparator.comparingInt(QualifierCoverage::statementsWith).reversed());
            props.add(new PropertyCoverage(a.pid, a.label, a.entitiesWith, a.totalStatements, quals));
        }
        // most-shared properties first, then most statements
        props.sort(Comparator.comparingInt(PropertyCoverage::entitiesWith)
                .thenComparingInt(PropertyCoverage::totalStatements).reversed());

        return new MergedStatementSummary(summaries.size(), props);
    }

    private static java.util.Set<String> distinctQualifierPids(EntityStatementSummary.Statement st) {
        java.util.Set<String> pids = new java.util.LinkedHashSet<>();
        for (EntityStatementSummary.Qualifier q : st.qualifiers()) {
            pids.add(q.pid());
        }
        return pids;
    }

    private static String qualifierLabel(EntityStatementSummary.Statement st, String qpid) {
        for (EntityStatementSummary.Qualifier q : st.qualifiers()) {
            if (q.pid().equals(qpid)) {
                return q.label();
            }
        }
        return qpid;
    }

    /** Compact coverage rendering with badges. */
    public String concise() {
        StringBuilder sb = new StringBuilder("samples: " + sampleCount + "\n");
        for (PropertyCoverage p : properties) {
            sb.append("  ").append(p.label()).append("  (").append(p.pid()).append(')')
              .append("  — ").append(p.entitiesWith()).append('/').append(sampleCount)
              .append(" entities, ").append(p.totalStatements()).append(" statements\n");
            for (QualifierCoverage q : p.qualifiers()) {
                sb.append("          ↳ ").append(q.label()).append(" (").append(q.pid()).append("): ")
                  .append(q.statementsWith()).append('/').append(p.totalStatements()).append('\n');
            }
        }
        return sb.toString();
    }

    private static final class Agg {
        final String pid;
        final String label;
        int entitiesWith;
        int totalStatements;
        final Map<String, QAgg> quals = new LinkedHashMap<>();

        Agg(String pid, String label) {
            this.pid = pid;
            this.label = label;
        }
    }

    private static final class QAgg {
        final String pid;
        final String label;
        int statementsWith;

        QAgg(String pid, String label) {
            this.pid = pid;
            this.label = label;
        }
    }
}
