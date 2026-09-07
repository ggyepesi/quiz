package wikidata.explore.transform;

import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Durable, object-independent account of self-reference decisions.
 *
 * <p>The transform findings point at live objects, including records that are then
 * removed from the pool. A snapshot cannot preserve those object references, so this
 * ledger freezes the identifying text needed to explain KEPT, DROPPED and SUSPECTED
 * outcomes after the snapshot is loaded again.</p>
 */
public record SelfReferenceLedger(boolean executed, List<Entry> entries) {
    public static final SelfReferenceLedger EMPTY = new SelfReferenceLedger(false, List.of());

    public enum Decision { KEPT, DROPPED, SUSPECTED }

    public record Entry(Decision decision, String type, String identifier, String name,
                        String witnessIdentifier, String witnessName,
                        List<String> identityFields, String reason,
                        quiz.source.WikidataStatementSource source,
                        quiz.source.WikidataStatementSource witnessSource) {
        public Entry {
            type = clean(type);
            identifier = clean(identifier);
            name = clean(name);
            witnessIdentifier = clean(witnessIdentifier);
            witnessName = clean(witnessName);
            identityFields = List.copyOf(identityFields == null ? List.of() : identityFields);
            reason = clean(reason);
        }
    }

    public SelfReferenceLedger {
        entries = List.copyOf(entries == null ? List.of() : entries);
        if (!executed && !entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "A self-reference rule that did not run cannot have decisions");
        }
    }

    public static SelfReferenceLedger ran(
            List<TransformEngine.SelfRefFinding> findings,
            List<WikidataDynamicObject> suspected) {
        List<Entry> entries = new ArrayList<>();
        for (TransformEngine.SelfRefFinding finding
                : findings == null ? List.<TransformEngine.SelfRefFinding>of() : findings) {
            entries.add(entry(
                    finding.decision() == TransformEngine.SelfRefDecision.DROPPED
                            ? Decision.DROPPED : Decision.KEPT,
                    finding.atom(), finding.witness(), finding.identityFields(), finding.reason()));
        }
        for (WikidataDynamicObject value
                : suspected == null ? List.<WikidataDynamicObject>of() : suspected) {
            entries.add(entry(Decision.SUSPECTED, value, null, List.of(),
                    "A same-slot witness exists for a surviving self-reference"));
        }
        return new SelfReferenceLedger(true, entries);
    }

    private static Entry entry(Decision decision, WikidataDynamicObject value,
                               WikidataDynamicObject witness, List<String> fields,
                               String reason) {
        return new Entry(decision,
                value == null ? "" : value.typeName(),
                value == null ? "" : value.getIdentifier(),
                value == null ? "" : value.getDisplayName(),
                witness == null ? "" : witness.getIdentifier(),
                witness == null ? "" : witness.getDisplayName(), fields, reason,
                statementSource(value), statementSource(witness));
    }

    private static quiz.source.WikidataStatementSource statementSource(
            WikidataDynamicObject value) {
        return value == null || value.wikidataStatementSources().isEmpty()
                ? null : value.wikidataStatementSources().getFirst();
    }

    public long count(Decision decision) {
        return entries.stream().filter(entry -> entry.decision() == decision).count();
    }

    private static String clean(String value) {
        return value == null ? "" : value;
    }
}
