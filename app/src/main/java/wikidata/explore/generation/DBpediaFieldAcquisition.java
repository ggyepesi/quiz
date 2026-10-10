package wikidata.explore.generation;

import datasource.api.SourceExecutionPlan;
import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.DBpediaEnrichment;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;
import java.util.ArrayList;

/**
 * Applies resolved DBpedia field bindings to every applicable class in a pool.
 *
 * <p>This is intentionally broader than the compatibility path it replaced, which
 * considered only the root class. Generate Domain and Enrich already hold the complete
 * graph; ignoring a configured source merely because its owner is referenced would make
 * the execution plan lie.
 */
public final class DBpediaFieldAcquisition {
    private DBpediaFieldAcquisition() { }

    public static Result apply(GeneratedProjectModel model,
            List<WikidataDynamicObject> pool, SourceExecutionPlan sourcePlan,
            WikidataSparqlClient dbpedia, GenerationLog log) throws Exception {
        if (model == null || pool == null || pool.isEmpty() || sourcePlan == null)
            return new Result(0, 0);
        GenerationLog sink = log == null ? GenerationLog.NOOP : log;
        DBpediaEnrichment acquisition = new DBpediaEnrichment();
        int fields = 0;
        int values = 0;
        for (Work work : worklist(model, sourcePlan, sink)) {
            GeneratedClassModel owner = work.owner();
            List<WikidataDynamicObject> targets = pool.stream()
                    .filter(object -> applies(model, object, owner, work.target())).toList();
            if (targets.isEmpty()) continue;
            fields++;
            values += acquisition.enrich(
                    targets, owner, dbpedia, sink::message, List.of(work.request()));
        }
        return new Result(fields, values);
    }

    private static List<Work> worklist(
            GeneratedProjectModel model, SourceExecutionPlan sourcePlan, GenerationLog log) {
        List<Work> result = new ArrayList<>();
        for (SourceExecutionPlan.Step step :
                sourcePlan.steps(datasource.api.BindingScope.FIELD_VALUE)) {
            var spec = step.prepared().configuration(
                    datasource.dbpedia.DbpediaDatasourceProvider.PropertySpec.class);
            if (spec == null) continue;
            GeneratedClassModel owner = model.findClass(step.target().className());
            if (owner == null) continue;
            String path = step.target().fieldPath();
            if (path.contains(".")) {
                log.message("DBpedia field skipped: " + owner.className() + "." + path
                        + " — nested paths are not yet supported.\n");
                continue;
            }
            var field = wikidata.explore.model.OwnedFieldSources.declaredField(
                    model, owner.className(), path);
            if (field == null) {
                log.message("DBpedia field skipped: " + owner.className() + "." + path
                        + " — the field no longer exists.\n");
                continue;
            }
            if (field.isNameField()) {
                log.message("DBpedia field skipped: " + owner.className() + "." + path
                        + " — name fields are supplied by class-name bindings.\n");
                continue;
            }
            result.add(new Work(owner, step.target(),
                    new DBpediaEnrichment.FieldRequest(field, spec.property(),
                            spec.fillOnlyMissing())));
        }
        return List.copyOf(result);
    }

    private static boolean applies(GeneratedProjectModel model,
            WikidataDynamicObject object, GeneratedClassModel owner,
            datasource.api.SourceBindingTarget target) {
        if (object == null) return false;
        if (target.contextual()) {
            wikidata.explore.model.OwnedComponentSite site =
                    wikidata.explore.model.OwnedComponentSite.parse(object.typeKey());
            return site != null && target.className().equals(site.targetClass())
                    && target.contextClassName().equals(site.ownerClass())
                    && target.contextFieldPath().equals(site.ownerField());
        }
        for (String direct : object.directClassNames()) {
            for (GeneratedClassModel current = model.findClass(direct); current != null;
                    current = current.baseClassName().isBlank() ? null
                            : model.findClass(current.baseClassName())) {
                if (owner.className().equals(current.className())) return true;
            }
        }
        return false;
    }

    public record Result(int fields, int values) { }

    private record Work(GeneratedClassModel owner,
            datasource.api.SourceBindingTarget target,
            DBpediaEnrichment.FieldRequest request) { }
}
