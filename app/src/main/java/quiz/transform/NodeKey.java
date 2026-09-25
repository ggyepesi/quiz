package quiz.transform;

import objectview.Viewable;

/**
 * How two stored references are decided to be one node of a reference graph.
 *
 * <p>Stable ⟨type, id⟩ identity where the value has one, reference identity otherwise —
 * an inline anonymous node is only ever itself. Every construct that walks stored
 * references shares this, so a grouping and the profile that decides whether grouping
 * is meaningful cannot disagree about how many nodes there are.
 */
final class NodeKey {
    private final Identity stable;
    private final Viewable reference;

    private NodeKey(Identity stable, Viewable reference) {
        this.stable = stable;
        this.reference = reference;
    }

    record Identity(String type, String id) {
        static Identity of(Viewable value) {
            if (value == null || value.getIdentifier() == null
                    || value.getIdentifier().isBlank()) return null;
            String type = value.identityTypeName();
            return new Identity(type == null ? "" : type, value.getIdentifier());
        }
    }

    static NodeKey of(Viewable value) {
        Identity identity = Identity.of(value);
        return identity == null ? new NodeKey(null, value) : stable(identity);
    }

    static NodeKey stable(Identity identity) {
        return new NodeKey(identity, null);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof NodeKey key)) return false;
        return stable != null || key.stable != null
                ? java.util.Objects.equals(stable, key.stable)
                : reference == key.reference;
    }

    @Override public int hashCode() {
        return stable != null ? stable.hashCode() : System.identityHashCode(reference);
    }
}
