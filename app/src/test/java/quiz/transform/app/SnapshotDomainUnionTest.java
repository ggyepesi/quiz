package quiz.transform.app;

import org.junit.jupiter.api.Test;
import quiz.transform.ui.DomainField;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotDomainUnionTest {

    private static WikidataDynamicObject wdo(String type, String name, String... kv) {
        WikidataDynamicObject o = new WikidataDynamicObject(name, name);
        o.type(type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            o.put(kv[i], kv[i + 1]);
        }
        return o;
    }

    @Test
    void fieldsAreTheUnionAcrossInstancesNotJustTheFirst() {
        // The FIRST laureate has no portrait; the union must still surface the field so
        // it shows in the config / coverage (as a gap), not vanish behind the sample.
        WikidataDynamicObject first = wdo("Laureate", "A");                 // name only
        WikidataDynamicObject second = wdo("Laureate", "B", "portrait", "p.jpg");
        SnapshotDomain domain = new SnapshotDomain(new ArrayList<>(List.of(first, second)));

        List<String> fields = domain.fields("Laureate").stream()
                .map(DomainField::field).toList();

        assertTrue(fields.contains("portrait"), "expected union to include portrait: " + fields);
    }

    @Test
    void representativeShapeSampleCarriesTheUnionFields() {
        WikidataDynamicObject first = wdo("Laureate", "A");
        WikidataDynamicObject second = wdo("Laureate", "B", "portrait", "p.jpg");
        SnapshotDomain domain = new SnapshotDomain(new ArrayList<>(List.of(first, second)));

        WikidataDynamicObject sample =
                (WikidataDynamicObject) domain.representativeSample("Laureate");

        assertTrue(sample.dynamicFieldValues().containsKey("portrait"));
    }

    @Test
    void representativeSampleDelegatesThroughTheCuratableWrapper() {
        // The live chain is WorkingDomain -> CuratableDomain -> SnapshotDomain; a wrapper
        // that forgets to delegate falls to the interface default (first instance) and
        // loses the union — the actual "no fields for Laureate" regression.
        WikidataDynamicObject first = wdo("Laureate", "A");
        WikidataDynamicObject second = wdo("Laureate", "B", "portrait", "p.jpg");
        SnapshotDomain base = new SnapshotDomain(new ArrayList<>(List.of(first, second)));
        CuratableDomain domain = new CuratableDomain(base,
                new quiz.curation.ManualCuration(new java.io.File("target/no-such.curation.json")));

        WikidataDynamicObject sample =
                (WikidataDynamicObject) domain.representativeSample("Laureate");

        assertTrue(sample.dynamicFieldValues().containsKey("portrait"));
    }

    @Test
    void nestedValueObjectFieldsAreUnionedRecursively() {
        WikidataDynamicObject firstMotivation = wdo("Motivation", "");
        firstMotivation.valueObject(true);
        firstMotivation.put("text", "for discovery");
        WikidataDynamicObject secondMotivation = wdo("Motivation", "");
        secondMotivation.valueObject(true);
        secondMotivation.put("topics", List.of("physics"));

        WikidataDynamicObject first = wdo("Laureate", "A");
        first.put("motivation", firstMotivation);
        WikidataDynamicObject second = wdo("Laureate", "B");
        second.put("motivation", secondMotivation);
        SnapshotDomain domain = new SnapshotDomain(new ArrayList<>(List.of(first, second)));

        List<String> fields = domain.fields("Laureate").stream()
                .map(DomainField::field).toList();

        assertTrue(fields.contains("motivation.text"), fields.toString());
        assertTrue(fields.contains("motivation.topics"), fields.toString());
    }

    @Test
    void emptyFirstCollectionDoesNotHideLaterCollectionShape() {
        WikidataDynamicObject first = wdo("Laureate", "A");
        first.put("topics", List.of());
        WikidataDynamicObject second = wdo("Laureate", "B");
        second.put("topics", List.of("physics"));
        SnapshotDomain domain = new SnapshotDomain(new ArrayList<>(List.of(first, second)));

        WikidataDynamicObject sample =
                (WikidataDynamicObject) domain.representativeSample("Laureate");

        assertTrue(sample.get("topics") instanceof List<?> topics
                && topics.size() == 1,
                "the graph sample must preserve populated collection shape");
    }

    @Test
    void nobelWrapperExposesMotivationAndLaureateChildren() {
        WikidataDynamicObject laureate = wdo("Laureate", "Louis Renault");
        laureate.put("portrait", "portrait.jpg");

        WikidataDynamicObject motivation = wdo("Motivation", "Motivation");
        motivation.valueObject(true);
        motivation.put("action", "promotion");
        motivation.put("topics", List.of("international law"));

        WikidataDynamicObject entry =
                wdo("LaureatesWithMotivation", "Louis Renault");
        entry.valueObject(true);
        entry.put("laureates", List.of(laureate));
        entry.put("motivation", motivation);

        WikidataDynamicObject prize = wdo("NobelPrize", "1907 PEACE");
        prize.put("laureatesWithMotivation", List.of(entry));
        SnapshotDomain domain =
                new SnapshotDomain(new ArrayList<>(List.of(prize, laureate)));

        List<String> fields = domain.fields("NobelPrize").stream()
                .map(DomainField::field).toList();

        assertTrue(fields.contains(
                "laureatesWithMotivation.motivation.action"), fields.toString());
        assertTrue(fields.contains(
                "laureatesWithMotivation.laureates.portrait"), fields.toString());
    }

}
