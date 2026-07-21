package quiz.web.sources;

import objectview.Viewable;
import objectview.field.FieldSet;
import quiz.Quizable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The first consistency check: per-field COVERAGE over a served pool — how many members
 * actually carry a value for a field path vs. how many are missing it. This automates
 * the by-hand decomposition (e.g. Nomination.ceremony 14154/14155, forWork.genre
 * 13499/14155). A gap is not necessarily an error — many are EXPECTED-missing (an
 * animated short with no P136 genre) — so this reports the numbers; judging expected
 * vs. violation is the next layer.
 */
public final class Coverage {

    /**
     * Coverage of one field path across the pool, judged against the field's declared
     * expectation. {@code verdict}: VIOLATION (a REQUIRED field is missing on some
     * member — an anomaly), GAP (an EXPECTED field is missing — a benign / enrich-able
     * coverage gap), or OK (no expectation, or fully covered).
     */
    public record FieldCoverage(
            String label, String path, int present, int total,
            String expectation, String verdict) {
        public int missing() { return total - present; }
        public double pct() {
            return total == 0 ? 0.0 : Math.round(1000.0 * present / total) / 10.0;
        }
    }

    private Coverage() {}

    /** Coverage + verdict for each declared dimension's path (ceremony, forWork.genre,
     *  …), given each path's declared expectation (NONE / EXPECTED / REQUIRED). */
    public static List<FieldCoverage> of(
            Collection<? extends Quizable> pool, List<Dimension> dimensions,
            Map<String, String> expectationByPath) {
        int total = pool.size();
        List<FieldCoverage> out = new ArrayList<>();
        for (Dimension d : dimensions) {
            int present = 0;
            for (Quizable q : pool) {
                if (hasValue(q, d.path())) {
                    present++;
                }
            }
            String expectation = expectationByPath == null
                    ? "NONE" : expectationByPath.getOrDefault(d.path(), "NONE");
            out.add(new FieldCoverage(
                    d.label(), d.path(), present, total,
                    expectation, verdict(expectation, total - present)));
        }
        return out;
    }

    private static String verdict(String expectation, int missing) {
        if (missing <= 0) {
            return "OK";
        }
        if ("REQUIRED".equals(expectation)) {
            return "VIOLATION";
        }
        if ("EXPECTED".equals(expectation)) {
            return "GAP";
        }
        return "OK";
    }

    /** Resolves a dotted path over one member and reports whether any value survives. */
    public static boolean hasValue(Quizable q, String path) {
        List<Object> current = new ArrayList<>();
        current.add(q);
        for (String seg : path.split("\\.")) {
            List<Object> next = new ArrayList<>();
            for (Object o : current) {
                if (o instanceof Viewable v) {
                    addFlattened(next, FieldSet.of(v).read(seg));
                }
            }
            current = next;
        }
        for (Object o : current) {
            if (o == null) {
                continue;
            }
            if (o instanceof String s && s.isBlank()) {
                continue;
            }
            if (o instanceof Collection<?> c && c.isEmpty()) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static void addFlattened(List<Object> out, Object v) {
        if (v instanceof Collection<?> c) {
            for (Object e : c) {
                addFlattened(out, e);
            }
        } else if (v != null) {
            out.add(v);
        }
    }
}
