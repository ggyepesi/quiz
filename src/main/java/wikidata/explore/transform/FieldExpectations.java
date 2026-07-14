package wikidata.explore.transform;

import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledField;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldExpectation;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Checks each reified class's field expectations (#96) against the transformed
 * pool. Separates the CHECK ("should be present") from the ACTION:
 * <ul>
 *   <li>{@code EXPECTED} — KEEP every record, but report coverage and count the
 *       ones missing the field (surfaced for curation, not deleted).</li>
 *   <li>{@code REQUIRED} — DROP records missing the field (the strict form).</li>
 * </ul>
 * Returns a per-field {@link FieldCoverage} report (so the truth about a field's
 * real coverage comes out after generation) plus the dropped records.
 */
public final class FieldExpectations {

    private FieldExpectations() {}

    public record FieldCoverage(
            String className, String fieldName, FieldExpectation level,
            int total, int present) {
        public int missing() {
            return total - present;
        }
    }

    public record Result(
            List<WikidataDynamicObject> dropped, List<FieldCoverage> coverage) {}

    private record Expected(String className, String fieldName,
                            FieldExpectation level) {}

    public static Result apply(
            GeneratedProjectModel project,
            Collection<WikidataDynamicObject> pool,
            GenerationLog log) {

        if (project == null || pool == null) {
            return new Result(new ArrayList<>(), new ArrayList<>());
        }
        return apply(rawExpectations(project), pool, log);
    }

    /** Compiled-model overload — same pool scan/drop, compiled expectation list. */
    public static Result apply(
            CompiledProjectModel project,
            Collection<WikidataDynamicObject> pool,
            GenerationLog log) {

        if (project == null || pool == null) {
            return new Result(new ArrayList<>(), new ArrayList<>());
        }
        return apply(compiledExpectations(project), pool, log);
    }

    private static List<Expected> rawExpectations(GeneratedProjectModel project) {
        List<Expected> out = new ArrayList<>();
        for (GeneratedClassModel cls : project.classes()) {
            if (cls == null || !cls.reifiesStatements()) {
                continue;
            }
            for (GeneratedFieldModel f : cls.fields()) {
                if (f == null || f.isNameField()) {
                    continue;
                }
                FieldExpectation level = f.expectation();
                if (level != FieldExpectation.NONE) {
                    out.add(new Expected(cls.className(), f.name(), level));
                }
            }
        }
        return out;
    }

    private static List<Expected> compiledExpectations(
            CompiledProjectModel project) {
        List<Expected> out = new ArrayList<>();
        for (CompiledClass cls : project.classes()) {
            if (!cls.statementClass()) {
                continue;
            }
            for (CompiledField f : cls.ownFields()) {
                FieldExpectation level = f.expectation();
                if (level != FieldExpectation.NONE) {
                    out.add(new Expected(cls.className(), f.name(), level));
                }
            }
        }
        return out;
    }

    private static Result apply(
            List<Expected> expectations,
            Collection<WikidataDynamicObject> pool,
            GenerationLog log) {

        List<WikidataDynamicObject> dropped = new ArrayList<>();
        List<FieldCoverage> coverage = new ArrayList<>();

        for (Expected e : expectations) {
            int total = 0;
            int present = 0;
            List<WikidataDynamicObject> missing = new ArrayList<>();
            for (WikidataDynamicObject o : pool) {
                if (o == null || !e.className().equals(o.typeName())) {
                    continue;
                }
                total++;
                if (isEmpty(o.get(e.fieldName()))) {
                    missing.add(o);
                } else {
                    present++;
                }
            }
            coverage.add(new FieldCoverage(
                    e.className(), e.fieldName(), e.level(), total, present));
            if (e.level() == FieldExpectation.REQUIRED) {
                dropped.addAll(missing);
            }
            if (log != null) {
                log.message("Expectation " + e.className() + "." + e.fieldName()
                        + " (" + e.level() + "): " + present + "/" + total
                        + " present, " + missing.size() + " missing"
                        + (e.level() == FieldExpectation.REQUIRED ? " → dropped"
                                : " (kept — see the present/missing facet)") + "\n");
            }
        }

        if (!dropped.isEmpty()) {
            Set<WikidataDynamicObject> drop =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            drop.addAll(dropped);
            pool.removeIf(drop::contains);
        }
        return new Result(dropped, coverage);
    }

    private static boolean isEmpty(Object v) {
        if (v == null) {
            return true;
        }
        if (v instanceof Collection<?> c) {
            return c.isEmpty();
        }
        if (v instanceof CharSequence s) {
            return s.toString().isBlank();
        }
        return false;
    }
}
