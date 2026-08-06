package quiz.enrichment;

import objectview.Viewable;
import objectview.field.DynamicFields;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies accepted enrichment decisions in memory to dynamic-field instances — used by the
 * ModelBuilder enrich pass to fill generated instances without a curation store. Each
 * decision's field values are written onto the instance whose identifier matches the
 * decision's subject; non-dynamic instances are skipped. Returns how many values were written.
 */
public final class EnrichmentApply {

    private EnrichmentApply() { }

    public static int toDynamicInstances(
            List<? extends Viewable> instances, List<EnrichmentDecision> decisions) {
        Map<String, Viewable> byId = new HashMap<>();
        for (Viewable instance : instances == null ? List.<Viewable>of() : instances) {
            if (instance != null) {
                byId.putIfAbsent(instance.getIdentifier(), instance);
            }
        }
        int applied = 0;
        for (EnrichmentDecision decision
                : decisions == null ? List.<EnrichmentDecision>of() : decisions) {
            Viewable instance = byId.get(decision.subject().targetId());
            if (!(instance instanceof DynamicFields dynamic)) {
                continue;
            }
            for (EnrichmentDecision.FieldDecision field : decision.fields()) {
                EnrichmentProposal.FieldCandidate candidate = field.candidate();
                dynamic.dynamicFieldValues().put(candidate.field(), candidate.proposedValue());
                applied++;
            }
        }
        return applied;
    }
}
