package wikidata.explore.codegen;

import wikidata.explore.extract.WikidataObjectRegistry;
import wikidata.explore.extract.WikidataDynamicObject;
import objectview.Viewable;
import objectview.viewconfig.ViewConfig;
import objectview.demo.CardFrame;
import wikidata.explore.model.FieldCardinality;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class GeneratedCanonicalCycleTestMain {

    public static void main(String[] args) throws Exception {
        GeneratedClassModel model =
                new GeneratedClassModel("Constellation");

        GeneratedFieldModel neighbours =
                model.addField(
                        "n",
                        FieldType.ENTITY,
                        FieldCardinality.COLLECTION);

        neighbours.entityClassName("Constellation");

        WikidataObjectRegistry registry = new WikidataObjectRegistry();

        WikidataDynamicObject a = registry.getOrCreate("Q1", "A");
        WikidataDynamicObject b = registry.getOrCreate("Q2", "B");

        a.put("n", new ArrayList<>(List.of(b)));
        b.put("n", new ArrayList<>(List.of(a)));

        System.out.println("Dynamic:");
        System.out.println("  a.n[0] == b ? "
                                   + (((List<?>) a.get("n")).getFirst() == b));
        System.out.println("  b.n[0] == a ? "
                                   + (((List<?>) b.get("n")).getFirst() == a));

        GeneratedViewableRuntime runtime =
                new GeneratedViewableRuntimeBuilder().build(model);

        GeneratedViewableMapper mapper =
                new GeneratedViewableMapper(runtime);

        List<Viewable> generated =
                mapper.mapRoots(List.of(a, b));

        Viewable ga = generated.get(0);
        Viewable gb = generated.get(1);

        Field nField = ga.getClass().getDeclaredField("n");
        nField.setAccessible(true);

        List<?> gaN = (List<?>) nField.get(ga);
        List<?> gbN = (List<?>) nField.get(gb);

        Object gaNeighbour = gaN.getFirst();
        Object gbNeighbour = gbN.getFirst();

        System.out.println();
        System.out.println("Generated:");
        System.out.println("  ga name=" + ga.getName()
                                   + " id=" + System.identityHashCode(ga));
        System.out.println("  gb name=" + gb.getName()
                                   + " id=" + System.identityHashCode(gb));

        System.out.println("  ga.n[0] == gb ? " + (gaNeighbour == gb));
        System.out.println("  gb.n[0] == ga ? " + (gbNeighbour == ga));

        System.out.println();
        System.out.println("Opening frame...");

        new CardFrame(
                "Generated canonical cycle test",
                ga,
                ViewConfig.allWithMinorFields(ga.getClass())
                          .setAddListener(true)
                          .setThumb(true));
    }
}