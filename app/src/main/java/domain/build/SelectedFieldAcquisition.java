package domain.build;

import domain.compile.transform.ReferentFieldLoad;
import domain.config.GeneratedProjectModel;
import domain.datasource.SourceRef;
import domain.datasource.api.SourceExecutionPlan;
import domain.datasource.api.SourceRuntimeServices;
import domain.datasource.evidence.ExtractedClaim;
import domain.datasource.evidence.SourceDocument;
import domain.datasource.wikidata.WikidataDatasourceProvider;
import domain.runtime.GenerationLog;
import domain.runtime.WikidataDynamicObject;
import wikidata.api.WikidataApiClient;
import work.CancellationToken;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Runs Enrich's field loaders on a detached set of write targets. Existing instances
 * remain available as dependencies, including loaded descendants and relation-route endpoints. */
public final class SelectedFieldAcquisition {
    private SelectedFieldAcquisition() { }

    public static boolean hasWork(GeneratedProjectModel model, SourceExecutionPlan plan) {
        return ReferentFieldLoad.hasSelectedClaims(model, plan)
                || StandardExternalSourceFamilies.create().families().stream()
                        .anyMatch(family -> family.configured(plan));
    }

    public record Read(WikidataDynamicObject member, Object value, SourceRef source,
            SourceDocument document, List<ExtractedClaim> claims, boolean answered, boolean failed) { }

    public record Result(List<Read> reads, List<String> failures, boolean cancelled) { }

    public static Result load(GeneratedProjectModel model, SourceExecutionPlan plan, String field,
            List<WikidataDynamicObject> members, List<WikidataDynamicObject> dependencies,
            SourceRuntimeServices services, GenerationLog log, CancellationToken cancellation)
            throws Exception {
        // The field starts empty on each staged copy: the read answers "what does the
        // source give now", and review compares that with the current value. Enrich's
        // loaders are additive and leave a populated field alone, so carrying the current
        // value over made every member that already had one keep it.
        List<WikidataDynamicObject> staged = new ArrayList<>(members.size());
        for (WikidataDynamicObject member : members) {
            WikidataDynamicObject copy = member.copyWithoutFields();
            copy.captureFieldSource(field);
            staged.add(copy);
        }
        List<String> failures = new ArrayList<>();
        boolean cancelled = false;
        try {
            cancellation.throwIfCancelled();
            if (ReferentFieldLoad.hasSelectedClaims(model, plan)) {
                var api = services.require(WikidataDatasourceProvider.ID, WikidataApiClient.class);
                try (var group = log.group("Load Wikidata claims for " + field)) {
                    ReferentFieldLoad.Result claims =
                            ReferentFieldLoad.loadSelected(model, staged, api, group, plan);
                    for (var failed : claims.failed()) {
                        failures.add(failed.fieldName() + " (" + failed.propertyPid()
                                + ") unavailable for " + String.join(", ", failed.coveredQids()));
                    }
                }
            }
            cancellation.throwIfCancelled();
            var sources = ExternalSourceAcquisition.applySelected(model, staged, dependencies,
                    plan, services, log, cancellation);
            failures.addAll(sources.failures());
        } catch (CancellationException | InterruptedException stopped) {
            cancelled = true;
        }
        List<Read> reads = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            var member = members.get(i);
            var copy = staged.get(i);
            Object value = copy.get(field);
            SourceRef source = copy.capturedFieldSource();
            if (value != null && source == null && !cancelled) {
                // Every loader reports where a value came from; a value without one would
                // reach review unsourced, so it is a defect reported, never a proposal.
                failures.add(field + " on " + member.getDisplayName()
                        + " was loaded without a recorded source and is not offered for review");
                value = null;
            }
            reads.add(new Read(member, value, source, copy.capturedFieldDocument(),
                    copy.capturedFieldClaims(), copy.capturedFieldAnswered(), copy.capturedFieldFailed()));
        }
        return new Result(List.copyOf(reads), List.copyOf(failures),
                cancelled || cancellation.isCancelled());
    }
}
