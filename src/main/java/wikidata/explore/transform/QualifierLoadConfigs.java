package wikidata.explore.transform;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a {@link QualifierLoadConfig} from DISCOVERED qualifiers — so capturing a
 * membership relation's qualifiers is generic (no per-domain hand-authoring). The
 * field name for each qualifier is camel-cased from its label ("for work" →
 * {@code forWork}); the kind comes from the discovered datatype.
 */
public final class QualifierLoadConfigs {

    // Bookkeeping qualifiers that carry no quiz value — dropped by default.
    private static final java.util.Set<String> NOISE = java.util.Set.of(
            "P805");   // "statement is subject of"

    private QualifierLoadConfigs() {}

    /** A discovered qualifier (pid + label + kind), as the probe surfaces it. */
    public record Discovered(String pid, String label, QualifierLoadConfig.Kind kind) {}

    /**
     * @param entityType   the class whose members carry the relation (typeName)
     * @param relationPid  the membership relation (e.g. P1411)
     * @param valueTypeQid optional P31 filter for the statement value (e.g. Q19020
     *                     keeps only Oscar categories); "" to keep all statements
     * @param discovered   the qualifiers to load (noise pre-filtered by the caller
     *                     or via {@link #withoutNoise})
     */
    public static QualifierLoadConfig fromQualifiers(String entityType,
                                                     String relationPid,
                                                     String valueTypeQid,
                                                     List<Discovered> discovered) {
        List<QualifierLoadConfig.Qualifier> quals = new ArrayList<>();
        for (Discovered d : discovered) {
            if (d != null && d.pid() != null && d.pid().matches("P\\d+")) {
                quals.add(new QualifierLoadConfig.Qualifier(
                        d.pid(), fieldName(d.label(), d.pid()), d.kind()));
            }
        }
        return new QualifierLoadConfig(
                entityType,
                relationPid,
                "statements",            // generic container field
                "Statement",             // generic per-statement type
                "value",                 // the relation's main value
                valueTypeQid == null ? "" : valueTypeQid,
                quals);
    }

    /**
     * The AID: from discovered qualifiers, propose a WHOLE transform — the
     * qualifier-load PLUS a canonical reify that de-denormalizes the relation.
     * Each ENTITY qualifier becomes a role (its value ∨ the subject), so a
     * relation stored on both endpoints (a film's {@code nominee} qualifier vs a
     * person's {@code forWork}) resolves to the same tuple and the dedup key
     * (the main value + every entity/year field) collapses the two sides into one
     * event. Generic: the user reviews/renames the result.
     */
    public static TransformConfig suggestTransform(String entityType,
                                                   String relationPid,
                                                   String valueTypeQid,
                                                   List<Discovered> discovered) {
        List<Discovered> clean = withoutNoise(discovered);
        QualifierLoadConfig load =
                fromQualifiers(entityType, relationPid, valueTypeQid, clean);

        List<ReifyConstruct.Role> roles = new ArrayList<>();
        List<String> dedup = new ArrayList<>();
        dedup.add(load.valueField());                 // the main value
        for (Discovered d : clean) {
            String f = fieldName(d.label(), d.pid());
            if (d.kind() == QualifierLoadConfig.Kind.ENTITY) {
                roles.add(new ReifyConstruct.Role(f, f, true));
                dedup.add(f);
            } else if (d.kind() == QualifierLoadConfig.Kind.YEAR) {
                dedup.add(f);
            }
        }

        TransformConfig tc = new TransformConfig();
        tc.qualifierLoads.add(load);
        if (!roles.isEmpty()) {
            tc.reifies.add(new ReifyConstruct(
                    entityType, load.statementField(), entityType + "Event",
                    "source", "value", true, roles, dedup));
        }
        return tc;
    }

    /** Drop bookkeeping qualifiers (e.g. P805) that carry no quiz value. */
    public static List<Discovered> withoutNoise(List<Discovered> discovered) {
        List<Discovered> out = new ArrayList<>();
        for (Discovered d : discovered) {
            if (d != null && !NOISE.contains(d.pid())) {
                out.add(d);
            }
        }
        return out;
    }

    /** "for work" → "forWork"; "point in time" → "pointInTime"; fallback = pid. */
    public static String fieldName(String label, String pid) {
        if (label == null || label.isBlank()) {
            return pid == null ? "field" : pid;
        }
        String[] words = label.trim().toLowerCase().split("[^a-z0-9]+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.isEmpty()) {
                sb.append(w);
            } else {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.isEmpty() ? (pid == null ? "field" : pid) : sb.toString();
    }
}
