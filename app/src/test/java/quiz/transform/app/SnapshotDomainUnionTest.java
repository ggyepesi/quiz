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
    void representativeSampleCarriesTheUnionFields() {
        WikidataDynamicObject first = wdo("Laureate", "A");
        WikidataDynamicObject second = wdo("Laureate", "B", "portrait", "p.jpg");
        SnapshotDomain domain = new SnapshotDomain(new ArrayList<>(List.of(first, second)));

        WikidataDynamicObject sample =
                (WikidataDynamicObject) domain.representativeSample("Laureate");

        assertEquals("p.jpg", sample.get("portrait"));
    }
}
