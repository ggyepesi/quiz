package objectview;

import flag.State;
import language.Language;
import objectview.field.ViewableFieldPaths;
import objectview.search.SearchAndSort;
import objectview.viewconfig.FieldTypeSource;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;
import quiz.transform.app.SnapshotDomain;
import quiz.transform.app.ViewableToWdo;
import domain.DomainModel;
import quiz.transform.ui.ReflectionDomain;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sorting a domain by a field reached THROUGH a nested collection reference
 * (State.languages -> Language.field), on a loaded snapshot (dynamic WDOs) —
 * the countries case. Guards that sort reads a nested path the same way the rest
 * of the app does, for both a stored field and the reference's display field.
 */
class NestedCollectionSortTest {

    private static State state(String name, String language, String speakers) {
        State s = new State(name);
        Language l = new Language(language);
        l.setSpeakers(speakers);
        s.getLanguages().add(l);
        return s;
    }

    private DomainModel snapshot(List<State> states) throws Exception {
        ReflectionDomain source = new ReflectionDomain(List.copyOf(states));
        File snap = File.createTempFile("nested-collection-sort", ".snapshot.json");
        snap.deleteOnExit();
        var converted = ViewableToWdo.convertDomain(source.memberRoots(), List.of(), source);
        new WikidataDynamicObjectJsonStore()
                .saveWithFieldGraph(converted.memberRoots(), snap, source);
        var loaded = new WikidataDynamicObjectJsonStore().loadAllWithFieldGraph(snap);
        return new SnapshotDomain(loaded.objects(), loaded.fieldGraph());
    }

    private List<Viewable> statesOf(DomainModel domain) {
        return domain.instances().stream()
                .filter(v -> "State".equals(v.typeName()))
                .map(Viewable.class::cast).toList();
    }

    private List<ViewableFieldPaths.PathInfo> nestedPath(DomainModel domain, String leaf) {
        ViewConfig cfg = new ViewConfig();
        cfg.setAllFields(false);
        ViewConfig langChild = new ViewConfig();
        langChild.setAllFields(false);
        langChild.addField(leaf, ViewConfig.leaf());
        cfg.addField("languages", langChild);
        FieldTypeSource schema = domain.fieldTypes("State");
        var paths = ViewableFieldPaths.collectFromSchema(cfg, schema, true);
        assertTrue(paths.stream().anyMatch(p -> p.dotted().equals("languages." + leaf)),
                "enumeration should produce languages." + leaf + " (got " +
                        paths.stream().map(ViewableFieldPaths.PathInfo::dotted).toList() + ")");
        return paths;
    }

    /** A STORED nested field (speakers) sorts. France's one language has 90 speakers,
     *  Germany's has 10 -> Germany (10) sorts first (lexical order of the number string). */
    @Test void sortsByStoredNestedCollectionField() throws Exception {
        DomainModel domain = snapshot(List.of(
                state("France", "Arabic", "90"),
                state("Germany", "Zulu", "10")));

        List<Viewable> sorted = new SearchAndSort()
                .sortViewables(statesOf(domain), nestedPath(domain, "speakers"));

        assertEquals("Germany", sorted.get(0).getDisplayName(),
                "sort by languages.speakers should order by the (nested) value");
    }

    /** The reference's DISPLAY field (name) must also sort. Language names are chosen to
     *  ORDER OPPOSITE the state names, so a working nested sort is distinguishable from the
     *  state-name tiebreaker: France speaks "Zulu", Germany speaks "Arabic" -> by language
     *  name Germany (Arabic) sorts first, though by state name France would. Currently FAILS
     *  on a snapshot: the display name is getDisplayName(), not a stored field, so the path
     *  extracts null and the order falls back to the state name (France first). */
    @Test void sortsByNestedCollectionDisplayField() throws Exception {
        DomainModel domain = snapshot(List.of(
                state("France", "Zulu", "90"),
                state("Germany", "Arabic", "10")));

        List<Viewable> sorted = new SearchAndSort()
                .sortViewables(statesOf(domain), nestedPath(domain, "name"));

        assertEquals("Germany", sorted.get(0).getDisplayName(),
                "sort by languages.name should order by the language display name (Arabic < Zulu)");
    }
}
