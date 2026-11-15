package discovery;

import datasource.api.SourceValueKind;
import datasource.api.discovery.DiscoveredRecordField;
import datasource.api.discovery.DiscoveredRelatedRecord;
import objectview.field.FieldKind;
import objectview.media.MediaValueData;
import objectview.demo.MultiView;
import objectview.render.Card;
import objectview.render.LinkRow;
import objectview.view.SearchableView;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Configured records still declare one literal ObjectView schema for every result. */
class DiscoveredRecordViewTest {

    @Test void configuredKindsAndSelectedReferencesBecomeObjectViewFields() {
        WikidataDynamicObject anubis = new WikidataDynamicObject("Q47534", "Anubis");
        anubis.type("EgyptianDeity");
        DiscoveredRecordView view = new DiscoveredRecordView(
                new DiscoveredRelatedRecord("met-object", "544075", "Statue of Anubis",
                        Map.of("image", List.of("https://images/anubis.jpg"),
                                "source", List.of("https://met/544075")),
                        List.of("deity\u0000Q47534")),
                "MuseumArtifact", List.of(
                        new DiscoveredRecordField("image", "Image",
                                SourceValueKind.MEDIA, false),
                        new DiscoveredRecordField("source", "Met source",
                                SourceValueKind.URL, false)), List.of(anubis));

        var fields = view.dynamicFieldSchema().fields();
        assertEquals(List.of("matchedSelectedEntities", "image", "source"),
                fields.stream().map(objectview.field.FieldRef::name).toList());
        assertTrue(fields.getFirst().reference());
        assertTrue(fields.getFirst().collection());
        assertEquals(FieldKind.MEDIA, fields.get(1).kind());
        assertTrue(fields.get(2).link());
        assertInstanceOf(MediaValueData.class, view.dynamicFieldValues().get("image"));
        assertEquals(List.of(anubis),
                view.dynamicFieldValues().get("matchedSelectedEntities"));
    }

    @Test void anOpenConfiguredRecordCardShowsItsDeclaredFields() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            DiscoveredRecordView record = record();
            MultiView multi = new MultiView();

            DiscoveredRecordView.addSection(multi, "MuseumArtifact", List.of(record));
            multi.build(1);

            SearchableView section = find(multi, SearchableView.class);
            assertTrue(section.search().getViewConfig().hasField("image"),
                    section.search().getViewConfig().getFields().keySet().toString());
            assertTrue(section.search().getViewConfig().hasField("source"),
                    section.search().getViewConfig().getFields().keySet().toString());
            assertTrue(section.renderContext().isCardOpen(
                    record, section.search().getViewConfig()),
                    "the down disclosure chip means the card body is open");

            Card card = (Card) section.cardList().getVirtualList().buildIfNeeded(record);
            assertTrue(card.hasRenderedConfiguredContent());
            assertTrue(findOrNull(card, objectview.media.ImagePane.class) != null,
                    "the configured image field is painted below the open caption");
            assertTrue(findOrNull(card, LinkRow.class) != null,
                    "the configured Met source link is painted below the open caption");
        });
    }

    private static DiscoveredRecordView record() {
        return new DiscoveredRecordView(
                new DiscoveredRelatedRecord("met-object", "544075", "Statue of Anubis",
                        Map.of("image", List.of("https://images/anubis.jpg"),
                                "source", List.of("https://met/544075")),
                        List.of()),
                "MuseumArtifact", List.of(
                        new DiscoveredRecordField("image", "Image",
                                SourceValueKind.MEDIA, false),
                        new DiscoveredRecordField("source", "Met source",
                                SourceValueKind.URL, false)), List.of());
    }

    private static <T> T find(java.awt.Container root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof java.awt.Container nested) {
                T found = findOrNull(nested, type);
                if (found != null) return found;
            }
        }
        throw new AssertionError("Missing component " + type.getSimpleName());
    }

    private static <T> T findOrNull(java.awt.Container root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        for (java.awt.Component child : root.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof java.awt.Container nested) {
                T found = findOrNull(nested, type);
                if (found != null) return found;
            }
        }
        return null;
    }
}
