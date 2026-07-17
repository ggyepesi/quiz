package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class FieldValueRestrictionsTest {

    private static WikidataDynamicObject ent(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }

    @Test void dropsValuesOutsideTheAllowedSet() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel c = new GeneratedClassModel("OscarNominations");
        GeneratedFieldModel target =
                c.addField("target", FieldType.ENTITY, FieldCardinality.COLLECTION);
        target.mapping().allowedQids().add("Q112243");   // Best Original Song (Oscar)
        project.addClass(c);

        WikidataDynamicObject song = ent("Q158549", "the song", "OscarNominations");
        WikidataDynamicObject oscar = ent("Q112243", "Best Original Song", "x");
        WikidataDynamicObject grammy = ent("Q1027904", "Grammy Song of the Year", "x");
        song.merge("target", oscar);
        song.merge("target", grammy);

        FieldValueRestrictions.apply(project, List.of(song));

        // Only the allowed Oscar category survives (one left → stored as a scalar).
        assertSame(oscar, song.get("target"), "Grammy category pruned, Oscar kept");
    }

    @Test void clearsFieldWhenNothingAllowed() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel c = new GeneratedClassModel("OscarNominations");
        c.addField("target", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .mapping().allowedQids().add("Q112243");
        project.addClass(c);

        WikidataDynamicObject e = ent("Q9", "e", "OscarNominations");
        e.merge("target", ent("Q1027904", "Grammy", "x"));   // only a non-allowed value

        FieldValueRestrictions.apply(project, List.of(e));

        assertNull(e.get("target"), "field removed when no value is allowed");
    }
}
