package wikidata.explore.transform;

import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledField;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldSourceMapping;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Derives {@code year ← YEAR(via.source)} projections from the model and applies
 * them via {@link TransformEngine#applyProjection} (source is a typed PATH, e.g.
 * {@code date.year}, so extraction is the path, not a convention). A field-level
 * TRANSFORM,
 * configured on the model but NOT a production kind: a DATE field keeps its own
 * primary source (e.g. year from the P585 qualifier), and this overlays the
 * authoritative referent date on top — so it composes rather than replaces.
 *
 * <p>Trigger: a {@code DATE} field with production {@code AUTO} (so it's not a
 * companion/invert) whose mapping names a reference to follow ({@code
 * subjectField} = via) and the date field to read off it ({@code matchValueField}
 * = source). E.g. Nomination.year (via = edition, source = date). Pulled OUT of
 * the StatementClass bundle — a plain construct in the shared vocabulary.
 */
public final class ModelYearProjections {

    private ModelYearProjections() {}

    public record YearProjection(String className, String via, String source, String field) {}

    public static int apply(GeneratedProjectModel project,
                            Collection<WikidataDynamicObject> pool,
                            GenerationLog log) {
        return apply(derive(project), pool, log, null);
    }

    /** As above, also collecting the records whose field was changed. A projection is
     *  overwrite-only, so on a settled pool this stays empty — which is what makes a
     *  non-empty answer the reportable event rather than the normal state. */
    public static int apply(GeneratedProjectModel project,
                            Collection<WikidataDynamicObject> pool,
                            GenerationLog log,
                            List<WikidataDynamicObject> changedOut) {
        return apply(derive(project), pool, log, changedOut);
    }

    /** Compiled-model overload — same projection application, compiled derivation. */
    public static int apply(CompiledProjectModel project,
                            Collection<WikidataDynamicObject> pool,
                            GenerationLog log) {
        return apply(derive(project), pool, log, null);
    }

    /** Compiled-model overload of {@link #apply(GeneratedProjectModel, Collection,
     *  GenerationLog, List)}. */
    public static int apply(CompiledProjectModel project,
                            Collection<WikidataDynamicObject> pool,
                            GenerationLog log,
                            List<WikidataDynamicObject> changedOut) {
        return apply(derive(project), pool, log, changedOut);
    }

    private static int apply(List<YearProjection> projections,
                             Collection<WikidataDynamicObject> pool,
                             GenerationLog log,
                             List<WikidataDynamicObject> changedOut) {
        if (projections.isEmpty()) {
            return 0;
        }
        TransformEngine engine = new TransformEngine();
        int total = 0;
        for (YearProjection p : projections) {
            List<WikidataDynamicObject> filled = new ArrayList<>();
            int changed = engine.applyProjection(
                    pool, p.className(), p.via(), p.source(), p.field(), filled);
            if (changedOut != null) {
                changedOut.addAll(filled);
            }
            total += changed;
            if (log != null) {
                log.message("Projected " + p.className() + "." + p.field()
                        + " <- " + p.via() + "." + p.source()
                        + ": " + changed + " changed\n");
            }
        }
        return total;
    }

    public static List<YearProjection> derive(GeneratedProjectModel project) {
        List<YearProjection> out = new ArrayList<>();
        if (project == null) {
            return out;
        }
        for (GeneratedClassModel cls : project.classes()) {
            for (GeneratedFieldModel f : cls.fields()) {
                if (f == null || f.type() != FieldType.DATE) {
                    continue;
                }
                FieldSourceMapping m = f.mapping();
                if (m.productionKind() != FieldProductionKind.AUTO) {
                    continue;   // leave companion/invert alone; only overlay a plain source
                }
                String via = trim(m.subjectField());
                String source = trim(m.matchValueField());
                if (via.isEmpty() || source.isEmpty()) {
                    continue;
                }
                out.add(new YearProjection(cls.className(), via, source, f.name()));
            }
        }
        return out;
    }

    /** Compiled-model overload of {@link #derive(GeneratedProjectModel)}. */
    public static List<YearProjection> derive(CompiledProjectModel project) {
        List<YearProjection> out = new ArrayList<>();
        if (project == null) {
            return out;
        }
        for (CompiledClass cls : project.classes()) {
            for (CompiledField f : cls.ownFields()) {
                if (f.type() != FieldType.DATE) {
                    continue;
                }
                if (f.source().productionKind() != FieldProductionKind.AUTO) {
                    continue;
                }
                String via = trim(f.source().subjectField());
                String source = trim(f.source().matchValueField());
                if (via.isEmpty() || source.isEmpty()) {
                    continue;
                }
                out.add(new YearProjection(cls.className(), via, source, f.name()));
            }
        }
        return out;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
