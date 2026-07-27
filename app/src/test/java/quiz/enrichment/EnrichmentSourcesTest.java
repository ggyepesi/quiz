package quiz.enrichment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import quiz.curation.IdentityLink;
import quiz.curation.ManualCuration;
import wikidata.explore.extract.WikidataDynamicObject;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnrichmentSourcesTest {

    @Test
    void includesApprovedSidecarSourceForTheTarget(@TempDir Path dir) {
        WikidataDynamicObject person = new WikidataDynamicObject("person-1", "John Macleod");
        person.type("Person");
        ManualCuration curation =
                new ManualCuration(dir.resolve("people.curation.json").toFile());
        curation.putIdentityLink(new IdentityLink(
                "Person", "person-1", "NobelPrize.org", "314",
                "https://www.nobelprize.org/laureate/314",
                "John Macleod", "manual"));
        curation.putIdentityLink(new IdentityLink(
                "Person", "someone-else", "DBpedia", "Other",
                "https://dbpedia.org/resource/Other", "Other", "manual"));

        var sources = EnrichmentSources.collect(person, "Person", curation);

        assertEquals(1, sources.size());
        assertEquals("NobelPrize.org", sources.get(0).kind());
        assertEquals("314", sources.get(0).sourceId());
    }
}
