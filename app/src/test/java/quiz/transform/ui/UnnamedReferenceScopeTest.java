package quiz.transform.ui;

import objectview.ViewableAdapter;
import objectview.field.FieldPath;
import org.junit.jupiter.api.Test;
import quiz.curation.ScopeFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import domain.DomainModel;

/**
 * A reference that points at something with no name is a gap the other scopes cannot see.
 *
 * <p>Such a member counts as PRESENT — the value IS there — so it never appears in a
 * missing-value worklist, even though the field renders as a bare QID everywhere. A
 * 20,000-film run finished with 2,707 references in exactly that state and nothing in the
 * curation UI could list them.
 */
class UnnamedReferenceScopeTest {

    private static Film film(String title, Place... places) {
        Film f = new Film(title);
        for (Place p : places) f.locations.add(p);
        return f;
    }

    /** A place whose label resolved. */
    private static Place named(String qid, String label) {
        return new Place(qid, label);
    }

    /** A place whose label never resolved: it shows its own QID. */
    private static Place unnamed(String qid) {
        return new Place(qid, qid);
    }

    @Test void aReferenceShowingItsOwnIdentifierCountsAsUnnamed() {
        assertTrue(FieldCoverageColumns.hasUnnamedReferenceInstance(
                film("12 Monkeys", unnamed("Q60")), FieldPath.parse("locations")));
        assertFalse(FieldCoverageColumns.hasUnnamedReferenceInstance(
                film("Fargo", named("Q60", "New York City")), FieldPath.parse("locations")));
    }

    /** A collection is a gap if ANY of its targets is unnamed — otherwise a film with
     *  four good locations and one bare QID would never be offered for curation. */
    @Test void oneUnnamedTargetAmongSeveralIsStillAGap() {
        assertTrue(FieldCoverageColumns.hasUnnamedReferenceInstance(
                film("Heat", named("Q65", "Los Angeles"), unnamed("Q1297")),
                FieldPath.parse("locations")));
    }

    @Test void anEmptyReferenceIsMissing_notUnnamed() {
        Film empty = film("Untitled");

        assertFalse(FieldCoverageColumns.hasUnnamedReferenceInstance(
                empty, FieldPath.parse("locations")),
                    "nothing to name — that is the MISSING scope's business");
    }

    @Test void aNonWikidataUnnamedViewableIsNotOfferedForWikidataRepair() {
        Place local = unnamed("local-42");

        assertFalse(FieldCoverageColumns.hasUnnamedReferenceInstance(
                film("Local", local), FieldPath.parse("locations")));
    }

    /**
     * A model that declares ONE value against data holding several. Not damage —
     * nothing failed to fetch — so it is reported apart from the gaps curation fills:
     * the fix is the declaration, and repairing instances would paper over a rule that
     * keeps producing it. 9,904 films carry several publication dates against a model
     * that says one.
     */
    @Test void severalValuesInASingleValuedFieldAreDetected() {
        Film one = film("Fargo", named("Q60", "New York City"));
        Film several = film("Heat", named("Q65", "Los Angeles"), named("Q1297", "Chicago"));

        assertTrue(FieldCoverageColumns.holdsSeveral(
                several, FieldPath.parse("locations")));
        assertFalse(FieldCoverageColumns.holdsSeveral(
                one, FieldPath.parse("locations")),
                    "one value is what the declaration promised");
        assertFalse(FieldCoverageColumns.holdsSeveral(
                film("Untitled"), FieldPath.parse("locations")),
                    "empty is a gap, not a contradiction");
    }

    /**
     * The scope's whole point: these members are PRESENT, so only the new filter
     * surfaces them.
     */
    @Test void theScopeSelectsExactlyTheMembersTheOthersHide() {
        Film good = film("Fargo", named("Q60", "New York City"));
        Film bare = film("12 Monkeys", unnamed("Q1297"));
        Film none = film("Untitled");
        List<Film> all = List.of(good, bare, none);
        DomainModel domain = new TestDomain(all);

        assertEquals(List.of(bare), select(domain, all, ScopeFilter.UNNAMED_REFERENCE));
        assertEquals(List.of(good, bare), select(domain, all, ScopeFilter.PRESENT),
                     "the unnamed one is PRESENT, which is why it needed its own scope");
        assertEquals(List.of(none), select(domain, all, ScopeFilter.MISSING));
    }

    /** An identity is not a reference, so the scope is not offered for an identity
     *  drill — absent rather than silently coerced into "unresolved". */
    @Test void theScopeIsOfferedForFieldTasksOnly() {
        assertTrue(List.of(ValidationPanel.scopeChoices(false))
                           .contains(ScopeFilter.UNNAMED_REFERENCE));
        assertFalse(List.of(ValidationPanel.scopeChoices(true))
                            .contains(ScopeFilter.UNNAMED_REFERENCE));
    }

    private static List<?> select(
            DomainModel domain, List<Film> all, ScopeFilter filter) {
        return FieldCoverageColumns.select(
                domain, all, "Film", FieldPath.parse("locations"), filter);
    }

    private record TestDomain(List<? extends objectview.Viewable> values)
            implements DomainModel {
        @Override public List<String> types() { return List.of("Film"); }
        @Override public objectview.field.FieldSchema fieldSchema(String type) {
            return List::of;
        }
        @Override public Collection<? extends objectview.Viewable> instances() {
            return values;
        }
        @Override public Class<? extends objectview.Viewable> universe() {
            return Film.class;
        }
    }

    public static final class Film extends ViewableAdapter {
        private final String title;
        private final Collection<Place> locations = new ArrayList<>();

        Film(String title) { this.title = title; }

        @Override public String getIdentifier() { return title; }
        @Override public String getDisplayName() { return title; }
        @Override public String typeName() { return "Film"; }
    }

    public static final class Place extends ViewableAdapter {
        private final String qid;
        private final String label;

        Place(String qid, String label) { this.qid = qid; this.label = label; }

        @Override public String getIdentifier() { return qid; }
        @Override public String getDisplayName() { return label; }
        @Override public String getReferenceLabel() { return label; }
        @Override public String typeName() { return "Place"; }
    }
}
