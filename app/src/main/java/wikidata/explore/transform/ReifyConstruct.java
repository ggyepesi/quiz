package wikidata.explore.transform;

/**
 * A Transform construct: explode a reference-list into one new object per
 * element. From {@code sourceType.listField} produce a {@code targetType} object
 * for every (source, element) pair, holding the source under {@code sourceField}
 * and the element under {@code elementField}.
 *
 * <p>e.g. reify({@code Film}, {@code awards}, {@code Nomination}, {@code film},
 * {@code award}) → one {@code Nomination{film, award}} per award a film won.
 *
 * <p>{@code promote}: when the list elements are already rich statement objects
 * (loaded by {@link QualifierLoader}, e.g. {@code AwardStatement{award, year}}),
 * set {@code promote=true} to <i>lift</i> each statement to a top-level
 * {@code targetType} in place — keeping its own fields (award, year, …) and
 * gaining the source back-ref under {@code sourceField} — instead of wrapping it
 * under {@code elementField}. That yields a flat {@code Nomination{film, award,
 * year}} rather than a nested {@code Nomination{film, award:{award, year}}}.
 */
public record ReifyConstruct(
        String sourceType,
        String listField,
        String targetType,
        String sourceField,
        String elementField,
        boolean promote,
        java.util.List<Role> roles,
        java.util.List<String> dedupBy,
        String primaryListField,
        java.util.List<String> subjectParticipantFields,
        wikidata.explore.model.CanonicalSpec.DuplicatePolicy duplicatePolicy) {

    /**
     * A CANONICAL field on a promoted event whose value is a qualifier ({@code
     * from}) when present, else the source entity ({@code fallbackToSource}).
     * This de-denormalizes a relation stored on both endpoints: a film's "Best
     * Actress" statement has a {@code nominee} qualifier (→ the person) but no
     * {@code forWork}, so {@code nominee = nomineeQual ∨ source} and {@code work =
     * forWorkQual ∨ source} resolve to the SAME (person, work) on both sides — and
     * {@link #dedupBy} then collapses the duplicate to one event.
     */
    public record Role(String field, String from, boolean fallbackToSource,
                       wikidata.explore.model.RoleKind kind) {

        /** Normalize a null kind to the default {@code REFERENCE}. */
        public Role {
            kind = kind == null ? wikidata.explore.model.RoleKind.REFERENCE : kind;
        }

        /** Back-compat: a role with no explicit kind is a {@code REFERENCE}. */
        public Role(String field, String from, boolean fallbackToSource) {
            this(field, from, fallbackToSource, wikidata.explore.model.RoleKind.REFERENCE);
        }
    }

    // Normalize nulls so older JSON (no roles/dedupBy) and call sites stay simple.
    public ReifyConstruct {
        roles = roles == null ? java.util.List.of() : roles;
        dedupBy = dedupBy == null ? java.util.List.of() : dedupBy;
        primaryListField = primaryListField == null ? "" : primaryListField;
        subjectParticipantFields = subjectParticipantFields == null
                ? java.util.List.of() : java.util.List.copyOf(subjectParticipantFields);
        duplicatePolicy = duplicatePolicy == null
                ? wikidata.explore.model.CanonicalSpec.DuplicatePolicy.KEEP_ONE
                : duplicatePolicy;
    }

    /** Back-compat 5-arg form (non-promoting, wraps the element). */
    public ReifyConstruct(String sourceType, String listField, String targetType,
                          String sourceField, String elementField) {
        this(sourceType, listField, targetType, sourceField, elementField,
                false, java.util.List.of(), java.util.List.of(), "",
                java.util.List.of(),
                wikidata.explore.model.CanonicalSpec.DuplicatePolicy.KEEP_ONE);
    }

    /** Back-compat 6-arg form (promote, no canonical roles/dedup). */
    public ReifyConstruct(String sourceType, String listField, String targetType,
                          String sourceField, String elementField, boolean promote) {
        this(sourceType, listField, targetType, sourceField, elementField,
                promote, java.util.List.of(), java.util.List.of(), "",
                java.util.List.of(),
                wikidata.explore.model.CanonicalSpec.DuplicatePolicy.KEEP_ONE);
    }

    /** 8-arg form (roles + dedup) without canonicalization. */
    public ReifyConstruct(String sourceType, String listField, String targetType,
                          String sourceField, String elementField, boolean promote,
                          java.util.List<Role> roles, java.util.List<String> dedupBy) {
        this(sourceType, listField, targetType, sourceField, elementField,
                promote, roles, dedupBy, "", java.util.List.of(),
                wikidata.explore.model.CanonicalSpec.DuplicatePolicy.KEEP_ONE);
    }

    /** Back-compat form without subject/list composition. */
    public ReifyConstruct(String sourceType, String listField, String targetType,
                          String sourceField, String elementField, boolean promote,
                          java.util.List<Role> roles, java.util.List<String> dedupBy,
                          String primaryListField) {
        this(sourceType, listField, targetType, sourceField, elementField,
                promote, roles, dedupBy, primaryListField, java.util.List.of(),
                wikidata.explore.model.CanonicalSpec.DuplicatePolicy.KEEP_ONE);
    }

    /** Back-compatible form with subject/list composition and historic keep-one policy. */
    public ReifyConstruct(String sourceType, String listField, String targetType,
                          String sourceField, String elementField, boolean promote,
                          java.util.List<Role> roles, java.util.List<String> dedupBy,
                          String primaryListField,
                          java.util.List<String> subjectParticipantFields) {
        this(sourceType, listField, targetType, sourceField, elementField,
                promote, roles, dedupBy, primaryListField, subjectParticipantFields,
                wikidata.explore.model.CanonicalSpec.DuplicatePolicy.KEEP_ONE);
    }

    /** Whether this reify canonicalizes by a primary list (the shared-award
     *  nominee list): records that HAVE it are the canonical events; records that
     *  lack it but carry an inverse role (a denormalized copy) are dropped. */
    public boolean canonicalizesByList() {
        return primaryListField != null && !primaryListField.isBlank();
    }
}
