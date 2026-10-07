package quiz.web;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Inline;
import objectview.annotations.Link;
import objectview.annotations.Reference;
import objectview.media.MediaValue;
import objectview.plan.Disclosure;
import objectview.plan.MockSink;
import objectview.render.ReferenceRow;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The web card shows what the executor decides, for every tick combination: the same
 * top-level fields as the mock trace, the same caption, the same navigation captions,
 * and every collection with the mock's size and member captions. The web used to
 * interpret the config itself (its own allFields reading and caption rule), so it
 * could drift from the desktop one config at a time.
 */
class WebFollowsTheMockTraceTest {

    private static final Person LANG = new Person("Fritz Lang", "1890-12-05");
    private static final Person HELM = new Person("Brigitte Helm", "1906-03-17");
    private static final Film FILM = new Film(LANG, List.of(HELM, LANG));

    /** Absent, the field alone, its DISPLAY, its other field, or both. */
    private static final List<List<String>> CHILDREN = List.of(
            List.of(), List.of("name"), List.of("birthDate"), List.of("name", "birthDate"));

    @TestFactory List<DynamicTest> theWebShowsWhatTheMockTraceShows() {
        List<DynamicTest> tests = new ArrayList<>();
        for (boolean caption : new boolean[] {false, true}) {
            for (int director = -1; director < CHILDREN.size(); director++) {
                for (int cast = -1; cast < CHILDREN.size(); cast++) {
                    ViewConfig config = ViewConfig.leaf();
                    if (caption) config.addField("title", ViewConfig.leaf());
                    config.addField("restored", ViewConfig.leaf());
                    config.addField("website", ViewConfig.leaf());
                    config.addField("poster", ViewConfig.leaf());
                    config.addField("details", child(List.of("label", "year")));
                    if (director >= 0) config.addField("director", child(CHILDREN.get(director)));
                    if (cast >= 0) config.addField("cast", child(CHILDREN.get(cast)));
                    tests.add(DynamicTest.dynamicTest(String.valueOf(describe(config)),
                            () -> assertParity(config)));
                }
            }
        }
        return tests;
    }

    private static void assertParity(ViewConfig config) {
        assertParity(config, (key, initiallyOpen) -> true);
        assertParity(config, (key, initiallyOpen) ->
                key instanceof Collection<?> ? false : initiallyOpen);
    }

    private static void assertParity(ViewConfig config, Disclosure disclosure) {
        MockSink mock = new MockSink();
        ViewableJson.executor(disclosure)
                .render(FILM, ViewableJson.literalFor(FILM, config), mock);
        MockSink.MockObject root = (MockSink.MockObject) mock.root();
        ViewableView web = ViewableJson.of(FILM, config, disclosure);

        assertObject(root, web);
    }

    private static void assertObject(MockSink.MockObject mock, ViewableView web) {
        List<MockSink.Mock> rendered = visible(mock.children());

        assertEquals(Objects.toString(mock.caption(), ""), web.name(), "caption");
        assertEquals(rendered.stream().map(WebFollowsTheMockTraceTest::fieldName).toList(),
                web.fields().stream().map(ViewableView.Field::name).toList(), "fields");
        for (int i = 0; i < rendered.size(); i++) {
            MockSink.Mock child = rendered.get(i);
            ViewableView.Field field = web.fields().get(i);
            if (child instanceof MockSink.MockNavigation navigation) {
                assertEquals(navigation.caption() == null
                                ? ReferenceRow.NAVIGATION_LABEL : navigation.caption(),
                        field.ref().name(), "navigation caption");
            } else if (child instanceof MockSink.MockBackReference backReference) {
                String expected = Objects.toString(backReference.caption(), "↩");
                String actual = field.ref() == null ? field.value() : field.ref().name();
                assertEquals(expected, actual, "back-reference caption");
            } else if (child instanceof MockSink.MockLeaf leaf) {
                switch (leaf.representation()) {
                    case TEXT -> assertEquals(
                            String.valueOf(leaf.value()), field.value(), "text value");
                    case LINK -> assertEquals(
                            String.valueOf(leaf.value()), field.url(), "link target");
                    case MEDIA -> assertEquals(
                            ((MediaValue) leaf.value()).mediaUrl(), field.url(), "media target");
                    default -> throw new AssertionError(leaf.representation());
                }
            } else if (child instanceof MockSink.MockObject object) {
                List<MockSink.Mock> body = visible(object.children());
                if (body.isEmpty()) {
                    assertEquals(Objects.toString(object.caption(), ""),
                            Objects.toString(field.value(), field.label()), "object value");
                } else {
                    assertEquals(1, field.nodes().size(), "one inline object");
                    assertObject(object, field.nodes().get(0));
                }
            } else if (child instanceof MockSink.MockCollection collection) {
                assertEquals(collection.size(), field.size(), "collection size");
                assertEquals(collection.expanded(), field.open(), "collection disclosure");
                if (!collection.expanded()) continue;
                assertEquals(collection.members().stream()
                                .filter(MockSink.MockObject.class::isInstance)
                                .map(MockSink.MockObject.class::cast)
                                .filter(m -> m.caption() != null || !visible(m.children()).isEmpty())
                                .map(m -> ((MockSink.MockObject) m).caption())
                                .filter(Objects::nonNull).toList(),
                        field.refs() == null ? List.of() : field.refs().stream()
                                .map(ViewableView.Ref::name)
                                .filter(name -> !name.isEmpty()).toList(),
                        "member captions");
            }
        }
    }

    private static List<MockSink.Mock> visible(List<MockSink.Mock> mocks) {
        return mocks.stream().filter(WebFollowsTheMockTraceTest::rendered).toList();
    }

    private static boolean rendered(MockSink.Mock mock) {
        return !(mock instanceof MockSink.MockSkip)
                && !(mock instanceof MockSink.MockDeferred);
    }

    private static String fieldName(MockSink.Mock mock) {
        List<String> segments = mock.occurrence().path().segments();
        return segments.isEmpty() ? "" : segments.get(segments.size() - 1);
    }

    private static ViewConfig child(List<String> fields) {
        ViewConfig child = ViewConfig.leaf();
        for (String field : fields) child.addField(field, ViewConfig.leaf());
        return child;
    }

    private static String describe(ViewConfig config) {
        StringBuilder out = new StringBuilder();
        config.getFields().forEach((name, child) -> out.append(name)
                .append(child.getFields().isEmpty() ? "" : child.getFields().keySet())
                .append(' '));
        return out.isEmpty() ? "nothing ticked" : out.toString().trim();
    }

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
        @SuppressWarnings("unused") private final boolean restored = false;
        @SuppressWarnings("unused") @Link private final String website =
                "https://example.org/metropolis";
        @SuppressWarnings("unused") private final Poster poster = new Poster();
        @SuppressWarnings("unused") @Inline private final Details details = new Details();

        private Film(Person director, List<Person> cast) {
            this.director = director;
            this.cast = cast;
        }
        @Override public String getIdentifier() { return title; }
        @Override public String getDisplayName() { return title; }
        @Override public String typeName() { return "Movie"; }
    }

    private static final class Poster implements MediaValue {
        @Override public String mediaLabel() { return "poster"; }
        @Override public String mediaUrl() { return "https://example.org/poster.png"; }
        @Override public boolean mediaSvg() { return false; }
    }

    private static final class Details extends ViewableAdapter {
        @DisplayField private final String label = "Restoration";
        @SuppressWarnings("unused") private final int year = 2010;
        @Override public String getIdentifier() { return label; }
        @Override public String getDisplayName() { return label; }
    }
}
