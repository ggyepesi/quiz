package wikidata.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WikidataTransportBenchmarkMainTest {
    @Test void selectsSizeQuantilesFromCompletedClaimsRequests() {
        StringBuilder log = new StringBuilder();
        for (int i = 1; i <= 8; i++) {
            log.append("[API ").append(i).append("] GET https://www.wikidata.org/w/api.php")
                    .append("?action=wbgetentities&ids=Q").append(i)
                    .append("&props=labels%7Cclaims%7Caliases&format=json\n")
                    .append("[API ").append(i).append("] OK headersMs=1 timeMs=2 bytes=")
                    .append(i * 100).append(" http=HTTP_2\n");
        }
        // A different projection is not part of the representative heavy wave.
        log.append("[API 9] GET https://www.wikidata.org/w/api.php?props=labels\n")
                .append("[API 9] OK headersMs=1 timeMs=2 bytes=9999 http=HTTP_2\n");

        var selected = WikidataTransportBenchmarkMain.representativeRequests(
                log.toString(), 3);

        assertEquals(3, selected.size());
        assertEquals(true, selected.stream().allMatch(
                uri -> uri.toString().contains("labels%7Cclaims%7Caliases")));
    }
}
