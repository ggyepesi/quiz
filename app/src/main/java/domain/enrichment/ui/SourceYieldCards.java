package domain.enrichment.ui;

import domain.enrichment.EnrichmentProposal;
import domain.enrichment.SourceYield;
import objectview.Viewable;
import domain.transform.DynamicViewable;

import java.util.ArrayList;
import java.util.List;

/**
 * What each configured source yielded, as cards a reader can open.
 *
 * <p>Discovery is a configured rule and what came back is what it accounts for, so it is
 * reported the way every other account is: buckets holding their instances, rendered
 * through the shared card machinery. What it replaced was seven numbers per source
 * concatenated onto the process summary line — unreadable, and not inspectable either.
 *
 * <p>These are deliberately NOT {@code RuleEffects}. That type's phases are the steps of
 * a generation pipeline, and a guard test holds every one of them to existing in both a
 * generation's and a remap's plan; asking a source what it has is a step in neither.
 * Borrowing the type would have meant either a phase that attaches to nothing or a
 * weakened guard, and the shape worth sharing — a titled bucket holding the instances it
 * accounts for — is shared already, because both render as ordinary Viewables.
 *
 * <p>A source that was examined and found nothing still gets a card: "asked, and it had
 * none" is the answer to why a member shows no value, and it is what a reader choosing
 * between sources most needs. One never reached gets none, because nothing is known.
 */
public final class SourceYieldCards {

    private SourceYieldCards() { }

    public static List<Viewable> of(List<SourceYield> yields) {
        List<Viewable> cards = new ArrayList<>();
        if (yields == null) return cards;
        for (SourceYield yield : yields) {
            if (yield == null || yield.examined() == 0) continue;
            List<Viewable> found = new ArrayList<>();
            yield.usableFields().forEach(candidate -> found.add(fieldCard(yield, candidate)));
            yield.usableMedia().forEach(candidate -> found.add(mediaCard(yield, candidate)));
            yield.standaloneInstances().forEach(candidate ->
                    found.add(instanceCard(yield, candidate)));
            DynamicViewable card = summaryCard("yield-" + yield.source(),
                    yield.source() + " (" + found.size() + ")", yield.examined() + " request(s)"
                    + (yield.failed() == 0 ? "" : ", " + yield.failed() + " failed")
                    + "; " + found.size() + " usable candidate(s) of "
                    + yield.candidates() + " proposed"
                    + (yield.corroborations() == 0 ? ""
                            : ", plus " + yield.corroborations() + " corroboration(s)")
                    + " in " + yield.elapsedMillis() + " ms");
            if (!found.isEmpty()) card.put("Candidates found", found);
            cards.add(card);
        }
        return cards;
    }

    /** A reported outcome alongside the proposal cards, using the same source report view. */
    public static Viewable summary(String title, List<String> lines) {
        return summaryCard("yield-" + title, title, String.join("\n", lines));
    }

    private static DynamicViewable summaryCard(String id, String title, String summary) {
        DynamicViewable card = new DynamicViewable(id, title);
        card.type("Source yield");
        card.put("Outcome", "Source yield");
        card.put("Summary", summary);
        return card;
    }

    static {
        // The fields of the cards this view builds, stated once (#363).
        objectview.field.RecordTypes.declare("Source yield",
                objectview.field.RecordTypes.text("Outcome"),
                objectview.field.RecordTypes.text("Summary"),
                objectview.field.RecordTypes.records("Candidates found", "Source candidate"));
        objectview.field.RecordTypes.declare("Source candidate",
                objectview.field.RecordTypes.text("Source"),
                objectview.field.RecordTypes.text("Field"),
                objectview.field.RecordTypes.value("Current value"),
                objectview.field.RecordTypes.value("Proposed value"),
                objectview.field.RecordTypes.text("Suggested action"),
                objectview.field.RecordTypes.value("Evidence"),
                objectview.field.RecordTypes.value("Image"),
                objectview.field.RecordTypes.text("Attribution"),
                objectview.field.RecordTypes.text("License"));
    }

    private static Viewable fieldCard(
            SourceYield yield, EnrichmentProposal.FieldCandidate candidate) {
        DynamicViewable view = new DynamicViewable(
                "yield-field-" + candidate.candidateId(),
                candidate.field() + " = " + candidate.proposedValue());
        view.type("Source candidate");
        view.put("Source", yield.source());
        view.put("Field", candidate.field());
        view.put("Current value", candidate.currentValue());
        view.put("Proposed value", candidate.proposedValue());
        view.put("Suggested action", candidate.suggestedAction().toString());
        view.put("Evidence", candidate.evidence());
        return view;
    }

    private static Viewable mediaCard(
            SourceYield yield, EnrichmentProposal.MediaCandidate candidate) {
        DynamicViewable view = new DynamicViewable(
                "yield-media-" + candidate.candidateId(),
                candidate.field() + " image");
        view.type("Source candidate");
        view.put("Source", yield.source());
        view.put("Field", candidate.field());
        view.put("Image", candidate.imageUrl());
        view.put("Attribution", candidate.attribution());
        view.put("License", candidate.license());
        return view;
    }

    private static Viewable instanceCard(
            SourceYield yield, EnrichmentProposal.InstanceCandidate candidate) {
        DynamicViewable view = new DynamicViewable(
                "yield-instance-" + candidate.candidateId(),
                "Create " + candidate.type() + " — " + candidate.displayName());
        view.type("Source candidate");
        view.put("Source", yield.source());
        view.put("Field", "Create " + candidate.type());
        view.put("Proposed value", candidate.displayName());
        view.put("Suggested action", "CREATE INSTANCE");
        view.put("Evidence", candidate.evidence());
        return view;
    }
}
