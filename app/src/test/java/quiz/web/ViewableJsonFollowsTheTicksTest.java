package quiz.web;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Reference;
import objectview.render.ReferenceRow;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The web card is a sink of the same executor as the desktop card: a field shows if
 * and only if it is ticked, DISPLAY is the caption, a scalar reference to an object
 * with its own card navigates ("Open" without its DISPLAY), a collection always shows
 * its size and starts folded, and a member fetched later renders under its
 * collection's config.
 */
class ViewableJsonFollowsTheTicksTest {

    private final Person lang = new Person("Fritz Lang", "1890-12-05");
    private final Person helm = new Person("Brigitte Helm", "1906-03-17");
    private final Film film = new Film(lang, List.of(helm, lang));

    @Test void theDefaultShowsEveryTopLevelFieldAndEachObjectsCaption() {
        ViewableView view = ViewableJson.of(film);

        assertEquals("Metropolis", view.name(), "DISPLAY is the caption");
        assertEquals(List.of("director", "cast", "crew"), names(view),
                "the caption is not repeated as a body field");

        ViewableView.Field director = field(view, "director");
        assertEquals("ref", director.kind());
        assertEquals("Fritz Lang", director.ref().id(), "a navigation chip");
        assertEquals("Fritz Lang", director.ref().name());

        ViewableView.Field cast = field(view, "cast");
        assertEquals(2, cast.size());
        assertEquals(Boolean.FALSE, cast.open(), "a collection starts folded");
        assertEquals(List.of("Brigitte Helm", "Fritz Lang"),
                cast.refs().stream().map(ViewableView.Ref::name).toList());
        assertTrue(cast.refs().stream().allMatch(ref -> ref.id() == null
                        && ref.inline() == null),
                "a member with only its caption ticked is the caption alone");

        ViewableView.Field crew = field(view, "crew");
        assertEquals(0, crew.size(), "an empty collection still shows its size");
    }

    @Test void anUntickedDisplayLeavesNoCaptionAndTheReferenceReadsOpen() {
        ViewConfig director = ViewConfig.leaf();
        director.addField("birthDate", ViewConfig.leaf());
        ViewConfig config = ViewConfig.leaf();
        config.addField("director", director);

        ViewableView view = ViewableJson.of(film, config);

        assertEquals("", view.name());
        assertEquals(List.of("director"), names(view), "unticked fields are absent");
        assertEquals(ReferenceRow.NAVIGATION_LABEL,
                field(view, "director").ref().name());
    }

    @Test void aMemberIsFetchedUnderItsCollectionsConfig() {
        ViewConfig member = ViewConfig.leaf();
        member.addField("name", ViewConfig.leaf());
        member.addField("birthDate", ViewConfig.leaf());
        ViewConfig config = ViewConfig.leaf();
        config.addField("cast", member);

        ViewableView.Ref first = field(ViewableJson.of(film, config), "cast").refs().get(0);

        assertEquals("Brigitte Helm", first.id(), "a member with a body is fetched by id");
        assertEquals(new ViewableView.Via("Movie", "Metropolis", "cast"), first.via());
        assertNull(first.inline());
    }

    @Test void theFetchedMemberShowsWhatItsCollectionTicks() {
        ViewConfig member = ViewConfig.leaf();
        member.addField("birthDate", ViewConfig.leaf());
        ViewConfig config = ViewConfig.leaf();
        config.addField("cast", member);
        ViewableView.Ref chip = field(ViewableJson.of(film, config), "cast").refs().get(0);

        ViewableView fetched = ViewableJson.member(helm, film, config, chip.via().path());

        assertEquals("", fetched.name(), "the member's DISPLAY is not ticked");
        assertEquals(List.of("birthDate"), names(fetched));
    }

    @Test void aTickedObjectWithNothingUnderItIsItsFieldNameAlone() {
        ViewConfig config = ViewConfig.leaf();
        config.addField("director", ViewConfig.leaf());

        ViewableView view = ViewableJson.of(film, config);

        assertEquals("ref", field(view, "director").kind(),
                "the director still has its own card, so it navigates");
        assertEquals(ReferenceRow.NAVIGATION_LABEL, field(view, "director").ref().name());
        assertEquals(List.of("director"), names(view));
    }

    private static List<String> names(ViewableView view) {
        return view.fields().stream().map(ViewableView.Field::name).toList();
    }

    private static ViewableView.Field field(ViewableView view, String name) {
        return view.fields().stream().filter(f -> name.equals(f.name()))
                .findFirst().orElseThrow(() -> new AssertionError(name + " not rendered"));
    }

    /** A served domain type: its typeName differs from the Java class, so it has a
     *  card of its own on the web. */
    private static final class Person extends ViewableAdapter {
        @DisplayField private final String name;
        @SuppressWarnings("unused") private final String birthDate;

        private Person(String name, String birthDate) {
            this.name = name;
            this.birthDate = birthDate;
        }
        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
        @Override public String typeName() { return "Human"; }
    }

    private static final class Film extends ViewableAdapter {
        @DisplayField private final String title = "Metropolis";
        @SuppressWarnings("unused") @Reference private final Person director;
        @SuppressWarnings("unused") private final List<Person> cast;
        @SuppressWarnings("unused") private final List<Person> crew = List.of();

        private Film(Person director, List<Person> cast) {
            this.director = director;
            this.cast = cast;
        }
        @Override public String getIdentifier() { return title; }
        @Override public String getDisplayName() { return title; }
        @Override public String typeName() { return "Movie"; }
    }
}
