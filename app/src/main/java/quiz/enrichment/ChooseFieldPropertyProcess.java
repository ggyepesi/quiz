package quiz.enrichment;

import process.Process;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessPlan;
import process.QuerySubprocess;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chooses the Wikidata property that sources a field — from a sample entity's REAL
 * claims, not a hardcoded name→property map. It reads the entity's properties (and their
 * labels), pauses for the user to pick one, and returns it. Used ONCE per field before a
 * fill batch, so every member is then read from the same chosen property.
 */
public final class ChooseFieldPropertyProcess implements Process<ChosenProperty> {

    private final String sampleQid;
    private final String field;
    private final String suggestedPid;
    private final WikimediaEntityLookup lookup;
    private final ProcessPlan plan;

    public ChooseFieldPropertyProcess(String sampleQid, String field, String suggestedPid) {
        this(sampleQid, field, suggestedPid, WikimediaEntityLookup.defaultFetcher());
    }

    ChooseFieldPropertyProcess(String sampleQid, String field, String suggestedPid,
                               WikimediaEntityLookup.JsonFetcher fetcher) {
        this.sampleQid = sampleQid;
        this.field = field == null ? "" : field;
        this.suggestedPid = suggestedPid;
        this.lookup = new WikimediaEntityLookup(fetcher);
        this.plan = new ProcessPlan(
                "Choose property",
                "Read the sample entity's properties, then choose which one sources this field",
                Map.of("field", this.field, "sample", sampleQid == null ? "" : sampleQid),
                List.of());
    }

    @Override public ProcessPlan plan() {
        return plan;
    }

    @Override public ProcessOutcome<ChosenProperty> execute(ProcessContext context)
            throws Exception {
        if (sampleQid == null || !sampleQid.matches("Q\\d+")) {
            return ProcessOutcome.failed(new IllegalArgumentException(
                    "A sample Wikidata QID is required to list properties"));
        }

        // 1. the sample entity's claims (precise values, all properties)
        ProcessOutcome<WikimediaEntityLookup.EntityRecord> entity =
                context.run(new QuerySubprocess<>(lookup.byQid(sampleQid)));
        if (entity.usefulResult().isEmpty()) {
            return ProcessOutcome.failed(entity.error() != null ? entity.error()
                    : new IllegalStateException("Could not read " + sampleQid));
        }
        WikimediaEntityLookup.EntityRecord record = entity.usefulResult().get();
        List<String> pids = new ArrayList<>(record.claims().keySet());

        // 2. English labels for those properties (API path, endpoint-agnostic)
        Map<String, String> labels =
                context.run(new QuerySubprocess<>(lookup.labels(pids)))
                        .usefulResult().orElse(Map.of());

        // 3. one option per property: label + an example value from this entity
        List<PropertyOption> options = new ArrayList<>();
        for (String pid : pids) {
            options.add(new PropertyOption(
                    pid, labels.getOrDefault(pid, pid), example(record, pid)));
        }
        options.sort(Comparator.comparing(option -> option.label().toLowerCase(Locale.ROOT)));

        if (context.cancellation().isCancelled()) {
            return ProcessOutcome.cancelled(null, "cancelled before choosing");
        }

        // 4. the user chooses
        ChosenProperty chosen = context.input(new PropertySelectionRequest(
                "Choose a property for \"" + field + "\"",
                "Pick the Wikidata property to fill \"" + field + "\" from.",
                field, options, suggestedPid));
        if (chosen == null || !chosen.isPresent()) {
            return ProcessOutcome.cancelled(null, "no property chosen");
        }
        return ProcessOutcome.succeeded(chosen, "chose " + chosen.pid());
    }

    /** A short example value for a property from the sample entity's first live claim. */
    private static String example(WikimediaEntityLookup.EntityRecord record, String pid) {
        for (WikimediaEntityLookup.Claim claim : record.claims(pid)) {
            if (claim.deprecated()) {
                continue;
            }
            Object raw = claim.value().value();
            if (raw instanceof Map<?, ?> map) {
                for (String key : List.of("amount", "time", "id")) {
                    Object part = map.get(key);
                    if (part != null) {
                        return part.toString();
                    }
                }
            } else if (raw instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return "";
    }
}
