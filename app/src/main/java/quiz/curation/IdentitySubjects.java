package quiz.curation;

import objectview.Viewable;
import wikidata.WikidataIds;

import java.util.ArrayList;
import java.util.List;

/**
 * Who, in a scope of instances, actually has a Wikidata entity identity to resolve
 * (#102).
 *
 * <p>Identity resolution searches Wikidata by an instance's display name and links
 * the best match. That is meaningful only for instances that ARE entities. Two kinds
 * are not, and offering them is worse than useless — a confident wrong link:
 *
 * <ul>
 *   <li><b>Statements.</b> A reified statement (a Nomination, a held position) is
 *       identified by a Wikidata statement id, {@code Q72717$67ADCA97-…}. It is already
 *       anchored, and it is a claim ABOUT an entity rather than a thing with a label of
 *       its own — its display name is borrowed from that entity. Searching by that name
 *       would link the statement to the entity it merely mentions. This is what made
 *       the {@code Nomination} class offer all 15,161 of its members as unresolved: the
 *       ids are not QIDs, so a QID test read every one as "not yet identified".</li>
 *   <li><b>Untyped instances.</b> Without a stable identity class there is nothing to
 *       write the link under; a link is keyed by ⟨type, id⟩.</li>
 * </ul>
 *
 * <p>The split is a value, not a filter applied in passing, so the caller can say what
 * it excluded and why instead of silently offering a shorter list.
 */
public record IdentitySubjects(
        List<Viewable> resolvable,
        List<Viewable> statements,
        List<Viewable> untyped) {

    public IdentitySubjects {
        resolvable = List.copyOf(resolvable == null ? List.of() : resolvable);
        statements = List.copyOf(statements == null ? List.of() : statements);
        untyped = List.copyOf(untyped == null ? List.of() : untyped);
    }

    /** Split {@code members} into the three groups, in one pass, preserving order. */
    public static IdentitySubjects of(List<Viewable> members) {
        List<Viewable> resolvable = new ArrayList<>();
        List<Viewable> statements = new ArrayList<>();
        List<Viewable> untyped = new ArrayList<>();

        for (Viewable member : members == null ? List.<Viewable>of() : members) {
            if (member == null) {
                continue;
            }
            if (WikidataIds.isStatementId(member.getIdentifier())) {
                statements.add(member);
            } else if (IdentityLinks.stableType(member) == null) {
                untyped.add(member);
            } else {
                resolvable.add(member);
            }
        }
        return new IdentitySubjects(resolvable, statements, untyped);
    }

    public boolean hasNothingToResolve() {
        return resolvable.isEmpty();
    }

    /** Why nothing can be resolved here — for the message that replaces the workflow. */
    public String excludedSummary() {
        return statements.size() + " statement(s) are already anchored; "
                + untyped.size() + " untyped instance(s) have no stable identity class.";
    }
}
