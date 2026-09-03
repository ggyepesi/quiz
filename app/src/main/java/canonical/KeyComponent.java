package canonical;

/**
 * One component of what identifies an instance.
 *
 * <p>A key is an ordered list of these. A component is either a value the candidate
 * carries — a field — or an identity its PRODUCTION supplies, which no field can hold
 * because it is a fact about where the candidate came from rather than about what it
 * says.
 *
 * <p>The structural kinds are not new behaviour. They are the branches
 * {@code Canonicalizer} already chooses between by asking the class kind, which is why an
 * entity class cannot currently be given a content key and a statement class cannot be
 * identified by its source. Naming them makes the choice the modeller's.
 */
public record KeyComponent(Kind kind, String fieldPath) {

    public enum Kind {
        /** A value the candidate carries, addressed by {@link #fieldPath()}. */
        FIELD,
        /**
         * The datasource's own identifier for the entity, provider-qualified, so two
         * datasources cannot collide by accident. What every entity class uses today.
         */
        SOURCE_IDENTITY,
        /**
         * The owner and the site that produced this part — a structured name is "the
         * name produced at Person.structuredName for Q42". Not its own field values,
         * deliberately: those can contradict each other.
         */
        OWNER_SITE_IDENTITY,
        /**
         * The candidate's own occurrence at its source, so each one stands alone.
         *
         * <p>This is what "surrogate identity" meant, and naming it is what removes the
         * hole where an empty key had to mean something. Selecting it is a decision;
         * inferring it from a blank field is the thing that is forbidden.
         */
        SOURCE_OCCURRENCE
    }

    public KeyComponent {
        if (kind == null) throw new IllegalArgumentException("A key component needs a kind");
        fieldPath = fieldPath == null ? "" : fieldPath.trim();
        if (kind == Kind.FIELD && fieldPath.isEmpty()) {
            throw new IllegalArgumentException("A field component needs a path");
        }
        if (kind != Kind.FIELD && !fieldPath.isEmpty()) {
            throw new IllegalArgumentException(
                    kind + " is supplied by production and addresses no field");
        }
    }

    public static KeyComponent field(String path) {
        return new KeyComponent(Kind.FIELD, path);
    }

    public static KeyComponent sourceIdentity() {
        return new KeyComponent(Kind.SOURCE_IDENTITY, "");
    }

    public static KeyComponent ownerSiteIdentity() {
        return new KeyComponent(Kind.OWNER_SITE_IDENTITY, "");
    }

    public static KeyComponent sourceOccurrence() {
        return new KeyComponent(Kind.SOURCE_OCCURRENCE, "");
    }

    /** Whether production supplies this component rather than the candidate's values. */
    public boolean structural() {
        return kind != Kind.FIELD;
    }

    @Override
    public String toString() {
        return kind == Kind.FIELD ? fieldPath : kind.name().toLowerCase().replace('_', ' ');
    }
}
