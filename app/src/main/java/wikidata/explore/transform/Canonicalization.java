package wikidata.explore.transform;

import wikidata.explore.compiled.CompiledCanonical;
import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.Canonicalizer;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.Collection;

/**
 * Generation-time canonicalization: sets each object's displayName from its
 * class's {@link CanonicalSpec}, so the SNAPSHOT (the raw WDO pool) stores the
 * configured name — e.g. a reified {@code Nomination} shows its {@code nominee}
 * rather than the reify "{forWork} — {category}" heuristic. Runs on the pool
 * before it's materialized/serialized, so web + desktop, typed + raw, all agree.
 *
 * <p>Only an EXPLICIT spec on a DERIVED class with a FIELD/TEMPLATE displayName
 * changes anything; entities (label) and legacy spec-less classes are untouched.
 * See docs/canonicalization-model.md.
 */
public final class Canonicalization {

    private Canonicalization() {}

    public static void apply(GeneratedProjectModel project,
                             Collection<WikidataDynamicObject> pool,
                             GenerationLog log) {
        if (project == null || pool == null) {
            return;
        }
        int scanned = 0, withModel = 0, withSpec = 0, derived = 0, changed = 0;
        String sample = null;
        for (WikidataDynamicObject o : pool) {
            if (o == null) {
                continue;
            }
            scanned++;
            GeneratedClassModel model = project.findClass(o.typeName());
            if (model == null) {
                continue;
            }
            withModel++;
            withSpec++;
            CanonicalSpec spec = model.canonical();
            if (!spec.isDerived()
                    || spec.displayNameMode() == CanonicalSpec.DisplayNameMode.LABEL) {
                continue;
            }
            derived++;
            String dn = Canonicalizer.displayName(spec, o::get, o.getDisplayName());
            if (sample == null) {
                sample = sample(o, spec, dn);
            }
            if (applyDisplayName(o, dn)) {
                changed++;
            }
        }
        logSummary(scanned, withModel, withSpec, derived, changed, sample, log);
    }

    /**
     * Compiled-model overload — reads the same explicit DERIVED-with-FIELD/TEMPLATE
     * spec off the compiled class and applies the identical displayName rule.
     */
    public static void apply(CompiledProjectModel project,
                             Collection<WikidataDynamicObject> pool,
                             GenerationLog log) {
        if (project == null || pool == null) {
            return;
        }
        int scanned = 0, withModel = 0, withSpec = 0, derived = 0, changed = 0;
        String sample = null;
        for (WikidataDynamicObject o : pool) {
            if (o == null) {
                continue;
            }
            scanned++;
            CompiledClass model = project.findClass(o.typeName()).orElse(null);
            if (model == null) {
                continue;
            }
            withModel++;
            CompiledCanonical canonical = model.canonical();
            withSpec++;
            if (!canonical.derivedIdentity()
                    || canonical.displayNameMode()
                    == CanonicalSpec.DisplayNameMode.LABEL) {
                continue;
            }
            derived++;
            CanonicalSpec spec = canonical.toSpec();
            String dn = Canonicalizer.displayName(spec, o::get, o.getDisplayName());
            if (sample == null) {
                sample = sample(o, spec, dn);
            }
            if (applyDisplayName(o, dn)) {
                changed++;
            }
        }
        logSummary(scanned, withModel, withSpec, derived, changed, sample, log);
    }

    private static boolean applyDisplayName(WikidataDynamicObject o, String dn) {
        if (dn != null && !dn.isBlank() && !dn.equals(o.getDisplayName())) {
            o.name(dn);
            return true;
        }
        return false;
    }

    private static String sample(WikidataDynamicObject o, CanonicalSpec spec, String dn) {
        Object fv = o.get(spec.displayNameField());
        return "type=" + o.typeName() + " field=" + spec.displayNameField()
                + " value=[" + (fv == null ? "null" : fv.getClass().getSimpleName())
                + "] '" + old(o) + "' -> '" + dn + "'";
    }

    private static void logSummary(int scanned, int withModel, int withSpec,
                                   int derived, int changed, String sample,
                                   GenerationLog log) {
        String summary = "Canonicalization: scanned=" + scanned + " withModel=" + withModel
                + " withSpec=" + withSpec + " derived=" + derived + " changed=" + changed
                + (sample == null ? "" : "  sample: " + sample);
        System.err.println("[canon] " + summary);   // reliable channel while diagnosing
        if (log != null) {
            log.message(summary + "\n");
        }
    }

    private static String old(WikidataDynamicObject o) {
        String n = o.getDisplayName();
        return n == null ? "" : n;
    }
}
