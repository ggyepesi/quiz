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
        String selectionId,
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
        selectionId = DeclarationIds.clean(selectionId);
        if (kind == Kind.UNBOUNDED
                && (!qids.isEmpty() || !relationPid.isBlank()
                        || !selectionName.isBlank() || !selectionId.isBlank())) {
            throw new IllegalArgumentException("An unbounded end carries no values");
        }
        if (kind != Kind.VOCABULARY && !selectionId.isBlank()) {
            throw new IllegalArgumentException("Only a vocabulary bound has a selection id");
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
        return new EntityBound(Kind.UNBOUNDED, List.of(), "", "", "", false);
    }

    /** Exactly these entities; {@link #unbounded()} when none of them is a QID. */
    public static EntityBound explicit(List<String> qids) {
        List<String> clean = onlyQids(qids);
        return clean.isEmpty() ? unbounded()
                : new EntityBound(Kind.EXPLICIT, clean, "", "", "", false);
    }

    /** The entities carrying {@code relationPid} into {@code targets}. */
    public static EntityBound relation(
            String relationPid, List<String> targets, boolean includeDescendants) {
        List<String> clean = onlyQids(targets);
        return clean.isEmpty() ? unbounded()
                : new EntityBound(Kind.RELATION, clean, relationPid, "", "", includeDescendants);
    }

    /**
     * The members of a named VOCABULARY Selection — by reference, so editing the
     * vocabulary still reaches every end bounded by it.
     */
    public static EntityBound vocabulary(String selectionName) {
        return vocabulary(selectionName, "");
    }

    /**
     * A vocabulary reference, carrying the declaration id that survives a rename.
     *
     * <p>The id is why this is a reference and not a name: renaming a Selection rebinds
     * every reference to it, and a bound holding only a name would silently stop
     * matching. Both ends carry it, so a rename reaches the subject exactly as it
     * reaches the object — the asymmetry that made this worth doing at all.
     */
    public static EntityBound vocabulary(String selectionName, String selectionId) {
        String name = selectionName == null ? "" : selectionName.trim();
        return name.isEmpty() ? unbounded()
                : new EntityBound(Kind.VOCABULARY, List.of(), "", name,
                        DeclarationIds.clean(selectionId), false);
    }

    /**
     * This bound with any vocabulary reference replaced by what it names.
     *
     * <p>The one crossing from authored to executable, for BOTH ends. A vocabulary is a
     * reference the project resolves; everything downstream then sees a bound it can
     * act on, and never has to know a reference existed. Anything else is returned
     * unchanged — a resolution must not quietly reshape a bound that needed none, which
     * is how a non-P31 relation and includeDescendants were being lost.
     *
     * @param values the vocabulary's members, empty if it has none
     * @param valueTypeQid the vocabulary's own type, blank if it has none
     */
    public EntityBound resolved(List<String> values, String valueTypeQid) {
        if (kind != Kind.VOCABULARY) return this;
        List<String> members = onlyQids(values);
        if (!members.isEmpty()) return explicit(members);
        String type = valueTypeQid == null ? "" : valueTypeQid.trim();
        return type.isEmpty() ? unbounded() : instancesOf(type);
    }

    /** The same bound, rebound to a selection that has been renamed. */
    public EntityBound rebound(String id, String name) {
        return kind == Kind.VOCABULARY ? vocabulary(name, id) : this;
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
