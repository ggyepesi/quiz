package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.JLabel;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationResultSourceLinkTest {
    @Test void resultCardsLeaveTheTitleForTheConfiguredDisplayLabel() {
        var statement = new wikidata.explore.extract.WikidataDynamicObject(
                "modeled-key", "Nomination");
        statement.wikidataStatementSources(List.of(
                new quiz.source.WikidataStatementSource(
                        "Q28$67ADCA97-2FF9-43AD-A4DC-0349086680AC",
                        "Q28", "P1411", "Q103916", "Best Actor")));

        var card = ModelBuilderFrame.instanceCards(List.of(statement)).getFirst();

        assertNull(card.decoration().get());
        assertEquals("Nomination", card.view().getDisplayName());
        assertEquals("Q28 — P1411 → Best Actor (Q103916)",
                ((quiz.source.WikidataStatementSource)
                        assertInstanceOf(List.class,
                                objectview.field.FieldSet.of(statement)
                                        .read("wikidataSource"))
                                .getFirst()).getDisplayName());
    }

    @Test void severalStatementSourcesCannotSqueezeOutTheDisplayLabel() throws Exception {
        var record = new wikidata.explore.extract.WikidataDynamicObject(
                "modeled-key", "Le Duc Tho — Nobel Peace Prize");
        record.wikidataStatementSources(List.of(
                new quiz.source.WikidataStatementSource(
                        "Q1$a", "Q1", "P166", "Q35637", "Nobel Peace Prize"),
                new quiz.source.WikidataStatementSource(
                        "Q2$b", "Q2", "P166", "Q35637", "Nobel Peace Prize")));
        objectview.render.Card[] rendered = new objectview.render.Card[1];

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            rendered[0] = new objectview.render.Card(
                    record, objectview.viewconfig.ViewConfig.all(record.getClass()),
                    new objectview.render.RenderContext(), false);
            rendered[0].setSize(260, 160);
            layoutTree(rendered[0]);
        });

        JLabel title = findLabel(rendered[0], record.getDisplayName());
        assertNotNull(title);
        assertTrue(title.getWidth() > 0,
                "the configured display label keeps title space");
    }

    @Test void savedMultiLaureateNobelCardKeepsItsLabelAndSourceField()
            throws Exception {
        File snapshot = new File(
                "../data/wikidata/nobelprizes/nobelprizes.snapshot.json");
        var storedRecord = new wikidata.explore.extract.WikidataDynamicObjectJsonStore()
                .loadAll(snapshot).stream()
                .filter(value -> "LaureatesWithMotivation".equals(value.typeName()))
                .filter(value -> "Le Duc Tho — Nobel Peace Prize"
                        .equals(value.getDisplayName()))
                .findFirst().orElseThrow();
        assertEquals(2, assertInstanceOf(List.class, storedRecord.get("laureates")).size(),
                "the fixture is the reported multi-laureate record");

        var model = new wikidata.explore.model.GeneratedProjectModelStore().load(
                new File("../data/wikidata/nobelprizes/nobelprizes.model.json"));
        var product = wikidata.explore.transform.ProductCompiler.compile(
                model, new java.util.ArrayList<>(List.of(storedRecord)));
        var controller = new quiz.transform.ui.TransformController(product, null);
        objectview.Viewable record = storedRecord;

        objectview.field.FieldSet fields = objectview.field.FieldSet.of(record);
        Object sourceValue = fields.read("wikidataSource");
        assertEquals(2, assertInstanceOf(List.class, sourceValue).size());
        assertEquals(objectview.field.FieldRole.PROVENANCE,
                fields.field("wikidataSource").role());

        objectview.render.Card[] rendered = new objectview.render.Card[1];
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            objectview.render.RenderContext context =
                    new objectview.render.RenderContext();
            context.setValueLinker(wikidata.ui.WikidataLinks.valueLinker());
            context.setFieldSchemaResolver(value ->
                    controller.renderedFieldSchema(value, record.typeName()));
            context.toggleCollectionExpanded(sourceValue, false);
            for (Object source : assertInstanceOf(List.class, sourceValue)) {
                context.toggleExpanded(source);
            }
            rendered[0] = new objectview.render.Card(
                    record, objectview.viewconfig.ViewConfig.all(record.getClass()),
                    context, false);
            rendered[0].setOpaque(true);
            rendered[0].setBackground(java.awt.Color.WHITE);
            java.awt.Dimension preferred = rendered[0].getPreferredSize();
            rendered[0].setSize(Math.max(720, preferred.width), preferred.height);
            layoutTree(rendered[0]);
        });

        JLabel title = findLabel(rendered[0], record.getDisplayName());
        assertNotNull(title, "the configured display label is the card header");
        assertTrue(title.getWidth() > 0,
                "the configured display label keeps title space");
        assertNotNull(findField(rendered[0], "wikidataSource"),
                "provenance is an ordinary expandable field below the header");
        assertNotNull(findField(rendered[0], "statementSubject"));
        assertNotNull(findField(rendered[0], "statementProperty"));
        assertNotNull(findField(rendered[0], "statementObject"));
        assertNull(findField(rendered[0], "statementValue"));
        assertNotNull(findField(rendered[0], "guid"));
        assertNotNull(findField(rendered[0], "statementJson"));

        File artifact = new File(
                "target/ui-artifacts/nobel-multi-laureate-source.png");
        assertTrue(artifact.getParentFile().mkdirs()
                        || artifact.getParentFile().isDirectory());
        BufferedImage image = new BufferedImage(
                rendered[0].getWidth(), rendered[0].getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        rendered[0].printAll(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", artifact);
    }

    @Test void savedMultiLaureateRecordIsFoundByEveryLaureatesDisplayName()
            throws Exception {
        File snapshot = new File(
                "../data/wikidata/nobelprizes/nobelprizes.snapshot.json");
        var loaded = new wikidata.explore.extract.WikidataDynamicObjectJsonStore()
                .loadAllWithFieldGraph(snapshot);
        domain.DomainModel domain = new quiz.transform.app.SnapshotDomain(
                loaded.objects(), loaded.fieldGraph());
        List<objectview.Viewable> records = domain.instances().stream()
                .filter(value -> "LaureatesWithMotivation".equals(value.typeName()))
                .map(objectview.Viewable.class::cast)
                .toList();
        objectview.Viewable medicine1974 = records.stream()
                .filter(value -> "George Emil Palade — Nobel Prize in Physiology or Medicine"
                        .equals(value.getDisplayName()))
                .findFirst().orElseThrow();

        objectview.viewconfig.FieldTypeSource schema =
                domain.fieldTypes("LaureatesWithMotivation");
        objectview.viewconfig.FieldTypeSource laureateSchema =
                schema.field("laureates").nested();
        String display = laureateSchema.fieldNames().stream()
                .filter(name -> laureateSchema.field(name).role()
                        == objectview.field.FieldRole.DISPLAY)
                .findFirst().orElse(
                        objectview.field.ViewableContractFieldSet.DISPLAY_KEY);
        objectview.viewconfig.ViewConfig laureate = new objectview.viewconfig.ViewConfig();
        laureate.setAllFields(false);
        laureate.addField(display, objectview.viewconfig.ViewConfig.leaf());
        objectview.viewconfig.ViewConfig search = new objectview.viewconfig.ViewConfig();
        search.setAllFields(false);
        search.addField("laureates", laureate);

        var paths = objectview.field.ViewableFieldPaths.collectFromSchema(
                search, schema, true);
        var hits = new objectview.search.SearchAndSort().searchViewables(
                records, List.of("duve"), paths);

        assertTrue(hits.values().stream().flatMap(List::stream)
                        .anyMatch(value -> value == medicine1974),
                "laureates → Display label searches every member of the list");
    }

    @Test void savedRequiredYearExcludesOnlyIncompleteAwardRecords()
            throws Exception {
        File snapshot = new File(
                "../data/wikidata/nobelprizes/nobelprizes.snapshot.json");
        var objects = new wikidata.explore.extract.WikidataDynamicObjectJsonStore()
                .loadAll(snapshot);

        List<wikidata.explore.extract.WikidataDynamicObject> awardRecords = objects.stream()
                .filter(value -> "LaureatesWithMotivation".equals(value.typeName()))
                .toList();
        assertEquals(714, awardRecords.size());
        assertTrue(awardRecords.stream().noneMatch(value -> {
            Object year = value.get("year");
            return year == null || year.toString().isBlank();
        }), "Expectation REQUIRED removes every award record without a year");

        assertTrue(objects.stream().anyMatch(value ->
                        "Q56509417".equals(value.getIdentifier())),
                "dropping an invalid award record must not erase its source entity");
        assertTrue(objects.stream().anyMatch(value ->
                        "Q629583".equals(value.getIdentifier())),
                "dropping an invalid award record must not erase its source entity");
    }

    @Test void restoredSelfReferenceDecisionLinksToItsStatementSource() {
        String statement = "Q28$67ADCA97-2FF9-43AD-A4DC-0349086680AC";
        var source = new quiz.source.WikidataStatementSource(
                statement, "Q28", "P1411", "Q103916", "Best Actor");
        var ledger = new wikidata.explore.transform.SelfReferenceLedger(true, List.of(
                new wikidata.explore.transform.SelfReferenceLedger.Entry(
                        wikidata.explore.transform.SelfReferenceLedger.Decision.KEPT,
                        "Nomination", statement, "Nomination", "", "",
                        List.of("category"), "no witness", source, null)));
        var effects = wikidata.explore.generation.RuleEffects.fromRun(
                List.of(),
                wikidata.explore.generation.GenerationRun.SelfReferenceAudit.restored(ledger),
                wikidata.explore.generation.GenerationRun.OwnedCompositionAudit.notRun(),
                wikidata.explore.generation.GenerationRun.KindClassificationAudit.notRun(),
                wikidata.explore.generation.GenerationRun.ProjectionAudit.notRun());
        var decision = effects.getFirst().instances().getFirst();

        var card = ModelBuilderFrame.instanceCards(List.of(decision)).getFirst();

        assertEquals(statement, decision.getIdentifier());
        assertNull(card.decoration().get());
        assertEquals(source,
                objectview.field.FieldSet.of(decision).read("wikidataSource"));
    }

    private static JLabel findLabel(java.awt.Component root, String text) {
        if (root instanceof JLabel label && text.equals(label.getText())) return label;
        if (root instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                JLabel found = findLabel(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static javax.swing.JComponent findField(
            java.awt.Component root, String fieldName) {
        if (root instanceof javax.swing.JComponent component
                && fieldName.equals(component.getClientProperty(
                objectview.field.FieldProperties.FIELD_NAME_PROPERTY))) {
            return component;
        }
        if (root instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                javax.swing.JComponent found = findField(child, fieldName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void layoutTree(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container nested) layoutTree(nested);
        }
    }
}
