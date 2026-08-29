package nobel.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import work.QueryContext;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Physics 2018 is the case worth pinning: three laureates, TWO achievements, and a
 * prize-level motivation over both. It is exactly what Wikidata cannot represent - there
 * the umbrella is hung on Mourou alone - so if the share grouping is wrong, the domain
 * silently loses the structure this source was chosen for.
 */
class NobelApiClientTest {

    private static NobelPrizeAward award(String fixture) throws Exception {
        try (InputStream in = NobelApiClientTest.class
                .getResourceAsStream("/nobel/" + fixture + ".json")) {
            return NobelApiClient.parse(new ObjectMapper().readTree(in)).getFirst();
        }
    }

    @Test void aDividedPrizeKeepsItsUmbrellaAndItsSharesApart() throws Exception {
        NobelPrizeAward physics = award("physics-2018");

        assertEquals(2018, physics.year());
        assertEquals("Physics", physics.category());
        assertEquals("for groundbreaking inventions in the field of laser physics",
                physics.topMotivation(),
                "the prize-level motivation belongs to the prize, not to one laureate");
        assertEquals(2, physics.shares().size(), "three laureates, two achievements");
    }

    @Test void laureatesCitedForTheSameAchievementFormOneShare() throws Exception {
        List<NobelPrizeAward.Share> shares = award("physics-2018").shares();

        assertEquals("for the optical tweezers and their application to biological systems",
                shares.getFirst().motivation());
        assertEquals("1/2", shares.getFirst().portion());
        assertEquals(List.of("Arthur Ashkin"), names(shares.getFirst()));

        assertEquals("for their method of generating high-intensity, ultra-short optical pulses",
                shares.get(1).motivation());
        assertEquals("1/4", shares.get(1).portion());
        assertEquals(2, names(shares.get(1)).size(), "the half shared by two");
        assertTrue(names(shares.get(1)).contains("Donna Strickland"));
    }

    @Test void everyLaureateSurvivesTheGroupingInSourceOrder() throws Exception {
        NobelPrizeAward physics = award("physics-2018");

        assertEquals(List.of("960", "961", "962"),
                physics.laureates().stream().map(NobelPrizeAward.Laureate::apiId).toList(),
                "the ids that join to Wikidata P8024, in sortOrder");
        assertEquals(List.of(1, 2, 3),
                physics.laureates().stream()
                        .map(NobelPrizeAward.Laureate::sortOrder).toList());
        assertFalse(physics.laureates().getFirst().organization());
    }

    @Test void anOrganisationIsNamedFromTheFieldTheSourceUsesForOne() throws Exception {
        NobelPrizeAward peace = award("peace-2020");

        NobelPrizeAward.Laureate wfp = peace.laureates().getFirst();
        assertEquals("World Food Programme", wfp.name(),
                "an organisation has orgName where a person has knownName");
        assertTrue(wfp.organization(), "the Peace Prize is not always won by a person");
        assertEquals("994", wfp.apiId());
        assertEquals("1", peace.shares().getFirst().portion(), "an undivided prize");
    }

    @Test void theCategoryQueryReportsWhatItReadAndRefusesAnUnknownCategory()
            throws Exception {
        NobelApiClient client = new NobelApiClient(uri -> {
            assertTrue(uri.toString().contains("nobelPrizeCategory=phy"), uri.toString());
            try (InputStream in = NobelApiClientTest.class
                    .getResourceAsStream("/nobel/physics-2018.json")) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        });

        List<NobelPrizeAward> awards = client.category("PHY").execute(new QueryContext());
        assertEquals(1, awards.size());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> client.category("chemistry"));
        assertTrue(refused.getMessage().contains("chemistry"),
                "the refusal names what was asked for");
    }

    @Test void aResponseWithNoPrizesYieldsNothingRatherThanFailing() throws Exception {
        assertEquals(List.of(), NobelApiClient.parse(
                new ObjectMapper().readTree("{\"nobelPrizes\":[]}")));
        assertEquals(List.of(), NobelApiClient.parse(null));
    }

    private static List<String> names(NobelPrizeAward.Share share) {
        return share.laureates().stream().map(NobelPrizeAward.Laureate::name).toList();
    }
}
