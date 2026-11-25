package domain.build;

import domain.datasource.api.SourceExecutionPlan;
import domain.datasource.api.SourceRuntimeServices;
import domain.runtime.GenerationLog;
import domain.runtime.WikidataDynamicObject;
import domain.config.GeneratedProjectModel;

import java.util.List;

/** One installable external field-acquisition family. */
public interface ExternalSourceFamily {
    String id();
    String displayName();
    default int summaryOrder() { return 0; }
    /**
     * Whether this family adds entity references to the pool. Such a family runs before
     * the others, and its new references are settled through semantic convergence (their
     * configured classes and fields) before any later family reads the pool. Its outcome
     * value count must count newly inserted references, not all values it can observe;
     * orchestration uses zero new values as the fixed-point condition.
     */
    default boolean producesEntityReferences() { return false; }
    boolean configured(SourceExecutionPlan plan);
    Outcome empty();
    Outcome acquire(Context context) throws Exception;

    record Context(GeneratedProjectModel model, List<WikidataDynamicObject> pool,
            SourceExecutionPlan plan, SourceRuntimeServices services, GenerationLog log,
            work.CancellationToken cancellation, List<WikidataDynamicObject> dependencies) { }

    record Outcome(
            String familyId,
            int values,
            String summary,
            int summaryOrder,
            List<String> warnings) {
        public Outcome(String familyId, int values, String summary, int summaryOrder) {
            this(familyId, values, summary, summaryOrder, List.of());
        }

        public Outcome {
            familyId = familyId == null ? "" : familyId;
            summary = summary == null ? "" : summary;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
