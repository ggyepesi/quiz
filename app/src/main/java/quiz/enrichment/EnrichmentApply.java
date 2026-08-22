package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;

import objectview.Viewable;

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
            if (instance == null) {
                continue;
            }
            // A map-held snapshot object and a hand-written bean are one question (#87):
            // FieldSet writes to whichever backing the instance has. A backing with no
            // settable field of that name refuses, and that decision is skipped — the
            // same outcome as before for an instance that could not take the value, but
            // now reached by asking rather than by testing the class.
            objectview.field.FieldSet fields = objectview.field.FieldSet.of(instance);
            for (EnrichmentDecision.FieldDecision field : decision.fields()) {
                if (field.action() == EnrichmentProposal.ReviewAction.CORROBORATE) continue;
                EnrichmentProposal.FieldCandidate candidate = field.candidate();
                try {
                    fields.write(candidate.field(), candidate.proposedValue());
                    applied++;
                } catch (RuntimeException refused) {
                    // no settable field of that name on this instance
                }
            }
        }
        return applied;
    }
}
