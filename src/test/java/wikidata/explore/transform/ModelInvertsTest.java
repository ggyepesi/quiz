package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelInvertsTest {

    private static GeneratedFieldModel entityField(String name, String pid, String ofClass) {
        GeneratedFieldModel f = new GeneratedFieldModel(
                name, FieldType.ENTITY, FieldCardinality.COLLECTION);
        f.entityClassName(ofClass);
        f.mapping().propertyPid(pid);
        return f;
    }

    private static GeneratedProjectModel oscarProject() {
        GeneratedClassModel oscar = new GeneratedClassModel("Oscarnominations");
        oscar.fields().add(entityField("categories", "P1411", "Category"));

        GeneratedClassModel category = new GeneratedClassModel("Category");
        GeneratedFieldModel nominees =
                entityField("nominees", "P1411", "Oscarnominations");
        nominees.mapping().productionKind(FieldProductionKind.INVERT);
        category.fields().add(nominees);

        GeneratedProjectModel p = new GeneratedProjectModel();
        p.rootClass(oscar);
        p.addClass(category);
        return p;
    }

    @Test void derivesInvertFromTheInvertField() {
        List<InvertConstruct> inverts = ModelInverts.derive(oscarProject());
        assertEquals(1, inverts.size());
        InvertConstruct c = inverts.get(0);
        assertEquals("Oscarnominations", c.sourceType());
        assertEquals("categories", c.refField());      // the forward field
        assertEquals("Category", c.targetType());
        assertEquals("nominees", c.backRefField());
    }

    @Test void invertFillsTheReverseFieldFromTheForwardReferences() {
        WikidataDynamicObject cat = obj("Q102427", "Best Picture", "Category");
        WikidataDynamicObject f1 = obj("Q1", "Film One", "Oscarnominations");
        WikidataDynamicObject f2 = obj("Q2", "Film Two", "Oscarnominations");
        f1.put("categories", List.of(cat));
        f2.put("categories", List.of(cat));
        List<WikidataDynamicObject> pool = new ArrayList<>(List.of(cat, f1, f2));

        InvertConstruct c = ModelInverts.derive(oscarProject()).get(0);
        new TransformEngine().applyInvert(pool, c);

        Object nominees = cat.get("nominees");
        assertTrue(nominees instanceof Collection<?>, "nominees should be a collection");
        Collection<?> ns = (Collection<?>) nominees;
        assertTrue(ns.contains(f1) && ns.contains(f2), ns.toString());
    }

    private static WikidataDynamicObject obj(String qid, String name, String type) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.type(type);
        return o;
    }
}
