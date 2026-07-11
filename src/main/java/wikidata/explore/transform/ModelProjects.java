package wikidata.explore.transform;

import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Turns a model's {@code production = PROJECT} fields into {@link ProjectConstruct}s,
 * so "this field's value lives on a referenced entity" is configured ON THE FIELD
 * (not in a separate Transform file) and applied automatically after generation.
 *
 * <p>A field {@code C.out} with production PROJECT reads {@code via.source}: it
 * reuses the mapping's {@code subjectField} as the reference to follow ({@code
 * via}) and {@code matchValueField} as the field to read off the referent ({@code
 * source}). E.g. {@code Nomination.year} (via = {@code edition}, source = {@code
 * date}) ← the ceremony edition's date. No query — the edition is already in the
 * pool with its own {@code date} field (once the Edition class is generated), and
 * QID identity unifies the qualifier reference with it.
 */
public final class ModelProjects {

    private ModelProjects() {}

    /** Derive every model projection and apply it to the pool in place (no query). */
    public static void apply(GeneratedProjectModel project,
                             Collection<WikidataDynamicObject> pool,
                             GenerationLog log) {
        List<ProjectConstruct> projects = derive(project);
        if (projects.isEmpty()) {
            return;
        }
        TransformEngine engine = new TransformEngine();
        for (ProjectConstruct c : projects) {
            engine.applyProject(pool, c);
            if (log != null) {
                log.message("Project " + c.targetType() + "." + c.outField()
                        + " <- " + c.viaField() + "." + c.sourceField() + "\n");
            }
        }
    }

    public static List<ProjectConstruct> derive(GeneratedProjectModel project) {
        List<ProjectConstruct> out = new ArrayList<>();
        if (project == null) {
            return out;
        }
        for (GeneratedClassModel cls : project.classes()) {
            for (GeneratedFieldModel f : cls.fields()) {
                if (f == null
                        || f.mapping().productionKind() != FieldProductionKind.PROJECT) {
                    continue;
                }
                String via = trim(f.mapping().subjectField());       // the reference to follow
                String source = trim(f.mapping().matchValueField()); // the field on the referent
                if (via.isEmpty() || source.isEmpty()) {
                    continue;
                }
                out.add(new ProjectConstruct(cls.className(), via, source, f.name()));
            }
        }
        return out;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
