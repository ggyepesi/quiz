package quiz.curation;

import java.util.Objects;

/** One independently executable item in a curation plan. Identity is deliberately a
 * distinct kind because it persists as an IdentityLink, while ordinary fields persist
 * as Corrections. */
public record CurationTask(
        Kind kind, String type, String field, ScopeFilter scopeFilter) {

    public enum Kind { IDENTITY, FIELD }

    public CurationTask {
        kind = Objects.requireNonNull(kind, "kind");
        type = Objects.requireNonNullElse(type, "");
        field = Objects.requireNonNullElse(field, "");
        scopeFilter = scopeFilter == null ? ScopeFilter.MISSING : scopeFilter;
        if (kind == Kind.FIELD && field.isBlank()) {
            throw new IllegalArgumentException("A field curation task needs a field path");
        }
    }

    public static CurationTask identity(String type, ScopeFilter state) {
        return new CurationTask(Kind.IDENTITY, type, "source.qid", state);
    }

    public static CurationTask field(
            String type, String field, ScopeFilter state) {
        return new CurationTask(Kind.FIELD, type, field, state);
    }

    @Override public String toString() {
        return (kind == Kind.IDENTITY ? "Identity (source.qid)" : field)
                + " — " + scopeFilter;
    }
}
