package work;

import objectview.ViewableAdapter;
import objectview.Viewable;
import objectview.annotations.DisplayField;
import objectview.annotations.Inline;
import objectview.annotations.Link;
import objectview.media.MediaValue;
import objectview.plan.MockSink;
import objectview.plan.ViewDefaults;
import objectview.render.RenderContext;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What is open and what is folded when a card first renders, checked in (#398).
 *
 * <p>Nothing else in the suite could see the start state a reader meets: the query log
 * opened every step's details one level below the step list, and ordinary cards opened
 * every nested object, while every test passed. This is to disclosure what
 * {@code panel-layout.txt} is to the editors: the artifact that can see the change.
 * Each fixture is rendered through the desktop card's own executor
 * ({@link RenderContext#executor()}) under its default View config, with no reader
 * choices; the web renders through the same executor decisions.
 *
 * <p>Regenerate with {@code -Ddisclosure.layout.write=true} and READ the diff — the file
 * is the agreement about how much a reader sees before clicking.
 */
class DisclosureIsCheckedInTest {

    private static final Path GOLDEN = Path.of("../docs/disclosure-layout.txt");

    @Test void cardsFirstRenderAsTheCheckedInLayout() throws IOException {
        StringBuilder actual = new StringBuilder();
        LogNode log = queryLog();
        describe(actual, "Query log - a generation run", log, context -> { });
        describe(actual, "Query log window - first render", log,
                wikidata.explore.query.swing.WorkflowLogWindow::applyLogDisclosure);
        describe(actual, "Query log window - a run, once opened", log, context -> {
            wikidata.explore.query.swing.WorkflowLogWindow.applyLogDisclosure(context);
            context.toggleCardExpanded(log);
        });
        describe(actual, "Query log - after opening steps", log,
                context -> context.setCollectionExpanded(log.steps(), true));
        LogNode first = log.steps().iterator().next();
        describe(actual, "Query log - after opening steps, then the first step", log,
                context -> {
                    context.setCollectionExpanded(log.steps(), true);
                    context.setExpanded(first, true);
                });
        Film film = new Film();
        describe(actual, "Card - a film with a director, cast, poster and inline details",
                film, context -> { });
        describe(actual, "Card - after opening cast and details", film, context -> {
            context.setCollectionExpanded(film.cast, true);
            context.setExpanded(film.details, true);
        });
        // The reader's setting on the search bar (#407): every top-level card starts as
        // its caption; opening one opens one level, as everywhere else.
        describe(actual, "Card - in a view set to Captions only", film,
                context -> context.setRootsStartFolded(true));
        describe(actual, "Card - Captions only, after opening the card", film, context -> {
            context.setRootsStartFolded(true);
            context.toggleCardExpanded(film);
        });
        // Switching back forgets the choices made under the other setting.
        describe(actual, "Card - back to open, after folding it and opening cast", film,
                context -> {
                    context.setRootsStartFolded(false);
                    context.toggleCardExpanded(film);
                    context.setCollectionExpanded(film.cast, true);
                    context.setRootsStartFolded(true);
                    context.setRootsStartFolded(false);
                });

        Path actualFile = GOLDEN.resolveSibling("disclosure-layout.actual.txt");
        if (Boolean.getBoolean("disclosure.layout.write")) {
            Files.writeString(GOLDEN, actual.toString());
            Files.deleteIfExists(actualFile);
            return;
        }
        String expected = Files.exists(GOLDEN) ? Files.readString(GOLDEN) : "";
        if (!expected.equals(actual.toString())) {
            Files.writeString(actualFile, actual.toString());
        } else {
            Files.deleteIfExists(actualFile);
        }
        assertEquals(expected, actual.toString(), "cards no longer first render like "
                + GOLDEN + "; diff it against " + actualFile + ", then re-run with "
                + "-Ddisclosure.layout.write=true to accept the new layout");
    }

    /** {@code clicks} records the reader's toggles the way the card's own controls do. */
    private static void describe(StringBuilder out, String title, Viewable root,
                                 java.util.function.Consumer<RenderContext> clicks) {
        RenderContext context = new RenderContext();
        clicks.accept(context);
        ViewConfig config = ViewDefaults.newView(context.shape(root));
        MockSink sink = new MockSink();
        context.executor().render(root, config, sink);
        out.append("== ").append(title).append(" ==\n");
        // Unticked fields are never painted; the artifact is what a reader sees.
        out.append(sink.trace().stream()
                .filter(line -> !line.stripLeading().startsWith("SKIP "))
                .collect(Collectors.joining("\n")));
        out.append("\n\n");
    }

    /** A run as the generation log records it: a step per phase, each with its request,
     *  messages and per-parent sub-queries. */
    private static LogNode queryLog() {
        LogNode run = new LogNode(LogKind.WORKFLOW, "Generate Constellations");
        LogNode stars = query("Load edge stars", "SPARQL",
                "SELECT ?value WHERE { ?value wdt:P59 ?parent }");
        stars.appendMessage("Loading edge \"stars\" for 95 parents (per-parent, limit 6)");
        stars.appendMessage("Edge \"stars\" complete: 507 child objects");
        stars.addStep(query("stars <- Q10476", "SPARQL",
                "SELECT ?value WHERE { ?value wdt:P59 wd:Q10476 }"));
        stars.addStep(query("stars <- Q8866", "SPARQL",
                "SELECT ?value WHERE { ?value wdt:P59 wd:Q8866 }"));
        stars.complete(LogStatus.OK, "507 child objects", null);
        run.addStep(stars);

        LogNode labels = query("Final label hydration", "API", "");
        labels.addStep(query("wbgetentities 50 entities", "API",
                "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=Q1|Q2"));
        labels.complete(LogStatus.OK, "138 resolved", null);
        run.addStep(labels);
        run.complete(LogStatus.OK, "1224 objects", null);
        return run;
    }

    private static LogNode query(String title, String type, String request) {
        LogNode node = new LogNode(LogKind.QUERY, title).queryType(type);
        if (!request.isBlank()) node.appendRequest(request);
        node.complete(LogStatus.OK, null, null);
        return node;
    }

    private static final class Person extends ViewableAdapter {
        @DisplayField private final String name;
        @SuppressWarnings("unused") private final String birthDate;
        Person(String name, String birthDate) {
            this.name = name;
            this.birthDate = birthDate;
        }
        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
        @Override public String typeName() { return "Human"; }
    }

    private static final class Film extends ViewableAdapter {
        private static final Person LANG = new Person("Fritz Lang", "1890-12-05");
        @DisplayField private final String title = "Metropolis";
        @SuppressWarnings("unused") private final Person director = LANG;
        private final List<Person> cast =
                List.of(new Person("Brigitte Helm", "1906-03-17"), LANG);
        @SuppressWarnings("unused") private final List<Poster> posters = List.of(new Poster());
        @SuppressWarnings("unused") @Link private final String website =
                "https://example.org/metropolis";
        @Inline private final Details details = new Details();
        @Override public String getIdentifier() { return title; }
        @Override public String getDisplayName() { return title; }
        @Override public String typeName() { return "Movie"; }
    }

    private static final class Poster implements MediaValue {
        @Override public String mediaLabel() { return "poster"; }
        @Override public String mediaUrl() { return "https://example.org/poster.png"; }
        @Override public boolean mediaSvg() { return false; }
        @Override public String toString() { return mediaLabel(); }
    }

    private static final class Details extends ViewableAdapter {
        @DisplayField private final String label = "Restoration";
        @SuppressWarnings("unused") private final int year = 2010;
        @Override public String getIdentifier() { return label; }
        @Override public String getDisplayName() { return label; }
    }
}
