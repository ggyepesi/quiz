package domain.enrichment;

import domain.config.FieldCardinality;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.api.SourceBindingTarget;
import domain.datasource.api.SourceRecipe;
import domain.datasource.schema.FieldType;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;
import domain.runtime.WikidataDynamicObject;
import objectview.field.FieldSet;
import objectview.field.LinkedTextSequence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class TextPassageLinksTest {
    @Test void theConfiguredPassageLinksEveryUnambiguousAcceptedNameWithoutChangingItsStoredText() {
        var odin = instance("Figure", "Q1", "Odin");
        var vili = instance("Figure", "Q2", "Vili");
        var otherVili = instance("Giant", "Q2", "Vili");
        var passage = instance("Excerpt", "chapter:1", "Chapter I");
        String words = "Odin met Vili, then Loki. ODIN left Odinsday behind.";
        passage.put("narrative", words);
        passage.put("participants", List.of(odin, vili, otherVili));

        var model = model();
        TextPassageLinks.install(model, List.of(odin, vili, otherVili, passage));
        var displayed = assertInstanceOf(LinkedTextSequence.class, FieldSet.of(passage).read("narrative"));
        assertEquals(words, displayed.text());
        var links = displayed.parts().stream().filter(LinkedTextSequence.Part::linked).toList();
        assertEquals(List.of("Odin", "ODIN"), links.stream().map(LinkedTextSequence.Part::text).toList(),
                "ambiguity, an unaccepted name and part of a word do not become links");
        assertSame(odin, links.getFirst().reference());
        assertSame(odin, links.getLast().reference());
        assertSame(words, passage.dynamicFieldValues().get("narrative"));

        passage.put("participants", List.of(vili));
        var revised = assertInstanceOf(LinkedTextSequence.class, FieldSet.of(passage).read("narrative"));
        assertSame(vili, revised.parts().stream().filter(LinkedTextSequence.Part::linked).findFirst()
                .orElseThrow().reference());
        assertEquals(1, revised.parts().stream().filter(LinkedTextSequence.Part::linked).count());
    }

    private static GeneratedProjectModel model() {
        var model = new GeneratedProjectModel();
        var work = new GeneratedClassModel("Book");
        model.rootClass(work);
        var field = work.addField("sections", FieldType.ENTITY, FieldCardinality.COLLECTION);
        field.entityClassName("Excerpt");
        field.sourceBindings().add(new SourceBinding(
                SourceBindingTarget.fieldValue("Book", "sections", SourceBindingSlot.CANDIDATE_EVIDENCE),
                new SourceRecipe(WikipediaDatasourceProvider.ID,
                        WikipediaDatasourceProvider.TEXT_PASSAGE_INSTANCES, Map.of(
                                WikipediaDatasourceProvider.TEXT_SOURCES, "source:1",
                                WikipediaDatasourceProvider.PASSAGE_TEXT_FIELD, "narrative",
                                WikipediaDatasourceProvider.PASSAGE_SOURCE_FIELD, "book",
                                WikipediaDatasourceProvider.PASSAGE_REFERENCES_FIELD, "participants"))));
        return model;
    }

    private static WikidataDynamicObject instance(String type, String id, String title) {
        var value = new WikidataDynamicObject(id, title);
        value.type(type);
        return value;
    }
}
