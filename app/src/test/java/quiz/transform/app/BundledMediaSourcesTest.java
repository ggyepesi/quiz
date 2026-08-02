package quiz.transform.app;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataMediaValue;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledMediaSourcesTest {

    @Test
    void repairsBareFlagTitleFromAnOlderSnapshotToTheBundledFile() {
        WikidataMediaValue flag = new WikidataMediaValue(
                "Flag of Christmas Island", "Flag of Christmas Island", true);
        WikidataDynamicObject state = new WikidataDynamicObject(
                "Christmas Island", "Christmas Island");
        state.put("flagVersions", List.of(flag));

        BundledMediaSources.resolve(List.of(state));

        assertTrue(flag.url().startsWith("file:"), flag.url());
        assertTrue(flag.url().endsWith("/flag/svg/Flag%20of%20Christmas%20Island.svg")
                        || flag.url().endsWith("/flag/svg/Flag of Christmas Island.svg"),
                flag.url());
    }
}
