package objectview;

import language.Language;
import objectview.field.ViewableFieldPaths;
import objectview.search.SearchAndSort;
import objectview.viewconfig.FieldTypeSource;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.SnapshotDomain;
import quiz.transform.app.ViewableToWdo;
import domain.DomainModel;
import quiz.transform.ui.ReflectionDomain;
import wikidata.explore.extract.WikidataDynamicObjectJsonStore;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A @Numeric field (Language.speakers) sorts by its NUMBER on a loaded snapshot — proving
 * the annotation persists as ORDERED and sort reads that kind (a dynamic path has no
 * reflection Field). Values are chosen so numeric and lexical order disagree.
 */
class NumericFieldSnapshotSortTest {

    private static Language lang(String name, String speakers) {
        Language l = new Language(name);
        l.setSpeakers(speakers);
        return l;
    }

    @Test void numericFieldSortsByValueNotLexically() throws Exception {
        // Numeric order: 20 (B) < 100 (A) < 3,000,000 (C).
        // Lexical order would be "100"(A) < "20"(B) < "3 million"(C) — a different sequence.
        Language a = lang("A", "100");
        Language b = lang("B", "20");
        Language c = lang("C", "3 million");

        ReflectionDomain source = new ReflectionDomain(List.of(a, b, c));
        File snap = File.createTempFile("numeric-sort", ".snapshot.json");
        snap.deleteOnExit();
        var converted = ViewableToWdo.convertDomain(source.memberRoots(), List.of(), source);
        new WikidataDynamicObjectJsonStore()
                .saveWithFieldGraph(converted.memberRoots(), snap, source);
        var loaded = new WikidataDynamicObjectJsonStore().loadAllWithFieldGraph(snap);
        DomainModel domain = new SnapshotDomain(loaded.objects(), loaded.fieldGraph());

        ViewConfig cfg = new ViewConfig();
        cfg.setAllFields(false);
        cfg.addField("speakers", ViewConfig.leaf());
        FieldTypeSource schema = domain.fieldTypes("Language");
        List<ViewableFieldPaths.PathInfo> paths =
                selectedPaths(cfg, schema, true);

        List<Viewable> languages = domain.instances().stream()
                .filter(v -> "Language".equals(v.typeName()))
                .map(Viewable.class::cast).toList();
        List<Viewable> sorted = new SearchAndSort().sortViewables(languages, paths);

        assertEquals(List.of("B", "A", "C"),
                sorted.stream().map(Viewable::getDisplayName).toList(),
                "speakers (@Numeric) must sort by value: 20 < 100 < 3 million");
    }

    // The selection's value paths (LiteralPaths.selection) against a class, sample or
    // schema shape; the old collectors each read the shorthand themselves.
    private static java.util.List<objectview.field.ViewableFieldPaths.PathInfo> selectedPaths(
            objectview.viewconfig.ViewConfig config, boolean excludeMedia) {
        return objectview.plan.LiteralPaths.selection(config,
                objectview.plan.TypeShape.ofClass(config.getCls()), excludeMedia);
    }

    private static java.util.List<objectview.field.ViewableFieldPaths.PathInfo> selectedPaths(
            objectview.Viewable sample, objectview.viewconfig.ViewConfig config,
            boolean excludeMedia) {
        return objectview.plan.LiteralPaths.selection(config,
                objectview.plan.TypeShape.ofSample(sample, null), excludeMedia);
    }

    private static java.util.List<objectview.field.ViewableFieldPaths.PathInfo> selectedPaths(
            objectview.viewconfig.ViewConfig config,
            objectview.viewconfig.FieldTypeSource schema, boolean excludeMedia) {
        return objectview.plan.LiteralPaths.selection(config,
                objectview.plan.TypeShape.of(schema), excludeMedia);
    }

    private static java.util.List<objectview.field.ViewableFieldPaths.PathInfo> selectedPaths(
            objectview.viewconfig.ViewConfig config) {
        return selectedPaths(config, true);
    }
}
