package wikidata.explore.model;

import datasource.EntityRef;
import datasource.api.acquisition.PopulationRequest;
import wikidata.WikidataIds;

import java.util.ArrayList;
import java.util.List;

/**
 * Which entities may occupy one end of a statement triple — the subject or the object.
 *
 * <p>One value, one way of being bounded. The alternatives were previously two
 * independent fields, an explicit QID set and a P31 type QID, and nothing stopped a model
 * setting both: the loader took the QIDs and the type filter silently did nothing, while
 * a vocabulary carrying its own type quietly overwrote the class's filter before that.
 * Two controls that looked combinable, one discarded without saying so.
 *
 * <p>Holding the choice in one value is what removes that. There is no state in which two
 * bounds compete, so no rule is needed to rank them and none can be written by accident.
 *
 * <p>This is the authored form; {@link PopulationRequest} is what a provider is asked to
 * fetch. {@link #toRequest} is the one crossing between them.
 */
public record EntityBound(
        Kind kind,
        List<String> qids,
        String relationPid,
        String selectionName,
        boolean includeDescendants) {

    public enum Kind {
        /** No bound: any entity may occupy this end. */
        UNBOUNDED,
        /** Exactly these entities. */
        EXPLICIT,
        /** The entities carrying {@code relationPid} into {@link #qids()}. */
        RELATION,
        /**
         * The members of a named VOCABULARY Selection.
         *
         * <p>A reference, not a copy. Resolving a vocabulary to its QIDs here would
         * freeze them: editing the vocabulary would stop reaching the models bounded by
         * it, which is the same reason an import is a live reference rather than a copy.
         */
        VOCABULARY
    }

    public EntityBound {
        if (kind == null) throw new IllegalArgumentException("An entity bound needs a kind");
        qids = List.copyOf(qids == null ? List.of() : qids);
        relationPid = relationPid == null ? "" : relationPid.trim();
        selectionName = selectionName == null ? "" : selectionName.trim();
        if (kind == Kind.UNBOUNDED
                && (!qids.isEmpty() || !relationPid.isBlank() || !selectionName.isBlank())) {
            throw new IllegalArgumentException("An unbounded end carries no values");
        }
        if (kind != Kind.VOCABULARY && !selectionName.isBlank()) {
            throw new IllegalArgumentException("Only a vocabulary bound names a selection");
        }
        if (kind == Kind.VOCABULARY) {
            if (selectionName.isBlank()) {
                throw new IllegalArgumentException("A vocabulary bound needs a name");
            }
            if (!qids.isEmpty() || !relationPid.isBlank()) {
                throw new IllegalArgumentException(
                        "A vocabulary bound is a reference, not a copy of its values");
            }
        }
        if (kind == Kind.EXPLICIT && !relationPid.isBlank()) {
            throw new IllegalArgumentException("An explicit bound has no relation");
        }
        if (kind == Kind.RELATION && !relationPid.matches("(?i)P\\d+")) {
            throw new IllegalArgumentException("A relation bound needs a property");
        }
        if (kind == Kind.EXPLICIT || kind == Kind.RELATION) {
            if (qids.isEmpty()) {
                throw new IllegalArgumentException(
                        "A bounded end needs at least one entity");
            }
        }
    }

    public static EntityBound unbounded() {
        return new EntityBound(Kind.UNBOUNDED, List.of(), "", "", false);
    }

    /** Exactly these entities; {@link #unbounded()} when none of them is a QID. */
    public static EntityBound explicit(List<String> qids) {
        List<String> clean = onlyQids(qids);
        return clean.isEmpty() ? unbounded()
                : new EntityBound(Kind.EXPLICIT, clean, "", "", false);
    }

    /** The entities carrying {@code relationPid} into {@code targets}. */
    public static EntityBound relation(
            String relationPid, List<String> targets, boolean includeDescendants) {
        List<String> clean = onlyQids(targets);
        return clean.isEmpty() ? unbounded()
                : new EntityBound(Kind.RELATION, clean, relationPid, "", includeDescendants);
    }

    /**
     * The members of a named VOCABULARY Selection — by reference, so editing the
     * vocabulary still reaches every end bounded by it.
     */
    public static EntityBound vocabulary(String selectionName) {
        String name = selectionName == null ? "" : selectionName.trim();
        return name.isEmpty() ? unbounded()
                : new EntityBound(Kind.VOCABULARY, List.of(), "", name, false);
    }

    /** The entities that are {@code P31} of {@code typeQid}. */
    public static EntityBound instancesOf(String typeQid) {
        return relation("P31", List.of(typeQid == null ? "" : typeQid), false);
    }

    public boolean bounded() {
        return kind != Kind.UNBOUNDED;
    }

    /**
     * What a provider is asked to fetch, or empty when this end is unbounded — there is
     * nothing to request, which is not the same as requesting nothing.
     */
    public java.util.Optional<PopulationRequest> toRequest(String namespace) {
        // A vocabulary names a Selection, and only the project can say what is in it.
        // Resolving it here would need a model this record deliberately does not have.
        if (!bounded() || kind == Kind.VOCABULARY) return java.util.Optional.empty();
        List<EntityRef> refs = new ArrayList<>();
        for (String qid : qids) refs.add(new EntityRef(namespace, qid));
        return java.util.Optional.of(kind == Kind.RELATION
                ? PopulationRequest.relation(namespace, relationPid, refs, includeDescendants)
                : PopulationRequest.explicit(namespace, refs));
    }

    private static List<String> onlyQids(List<String> values) {
        List<String> clean = new ArrayList<>();
        if (values == null) return clean;
        for (String value : values) {
            String trimmed = value == null ? "" : value.trim();
            if (WikidataIds.isQid(trimmed) && !clean.contains(trimmed)) clean.add(trimmed);
        }
        return clean;
    }
}
