package quiz.web;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Reference;
import objectview.plan.MockSink;
import objectview.render.ReferenceRow;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
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
        MockSink mock = new MockSink();
        ViewableJson.executor((key, initiallyOpen) -> true)
                .render(FILM, ViewableJson.literalFor(FILM, config), mock);
        MockSink.MockObject root = (MockSink.MockObject) mock.root();
        ViewableView web = ViewableJson.of(FILM, config);

        assertEquals(Objects.toString(root.caption(), ""), web.name(), "caption");
        assertEquals(root.children().stream().map(WebFollowsTheMockTraceTest::at).toList(),
                web.fields().stream().map(ViewableView.Field::name).toList(), "fields");
        for (MockSink.Mock child : root.children()) {
            ViewableView.Field field = web.fields().stream()
                    .filter(f -> f.name().equals(at(child))).findFirst().orElseThrow();
            if (child instanceof MockSink.MockNavigation navigation) {
                assertEquals(navigation.caption() == null
                                ? ReferenceRow.NAVIGATION_LABEL : navigation.caption(),
                        field.ref().name(), "navigation caption");
            } else if (child instanceof MockSink.MockCollection collection) {
                assertEquals(collection.size(), field.size(), "collection size");
                assertEquals(collection.members().stream()
                                .map(m -> ((MockSink.MockObject) m).caption())
                                .filter(Objects::nonNull).toList(),
                        field.refs() == null ? List.of() : field.refs().stream()
                                .map(ViewableView.Ref::name)
                                .filter(name -> !name.isEmpty()).toList(),
                        "member captions");
            }
        }
    }

    private static String at(MockSink.Mock mock) {
        return switch (mock) {
            case MockSink.MockObject o -> o.at();
            case MockSink.MockCollection c -> c.at();
            case MockSink.MockNavigation n -> n.at();
            case MockSink.MockBackReference b -> b.at();
            case MockSink.MockLeaf l -> l.at();
        };
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

        private Film(Person director, List<Person> cast) {
            this.director = director;
            this.cast = cast;
        }
        @Override public String getIdentifier() { return title; }
        @Override public String getDisplayName() { return title; }
        @Override public String typeName() { return "Movie"; }
    }
}
