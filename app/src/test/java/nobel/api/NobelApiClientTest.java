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
 * the umbrella is hung on Mourou alone - so if the achievement grouping is wrong, the
 * domain silently loses the structure this source was chosen for.
 */
class NobelApiClientTest {

    private static NobelPrizeAward award(String fixture, String categoryCode) throws Exception {
        try (InputStream in = NobelApiClientTest.class
                .getResourceAsStream("/nobel/" + fixture + ".json")) {
            return NobelApiClient.parse(
                    new ObjectMapper().readTree(in), categoryCode).getFirst();
        }
    }

    @Test void aDividedPrizeKeepsItsUmbrellaAndItsAchievementsApart() throws Exception {
        NobelPrizeAward physics = award("physics-2018", "phy");

        assertEquals("phy", physics.categoryCode(),
                "the category-filtered request supplies the code absent from the response");
        assertEquals(2018, physics.year());
        assertEquals("Physics", physics.category());
        assertEquals("for groundbreaking inventions in the field of laser physics",
                physics.topMotivation(),
                "the prize-level motivation belongs to the prize, not to one laureate");
        assertEquals(2, physics.achievements().size(),
                "three laureate awards, two achievements");
    }

    @Test void laureatesCitedForOneAchievementKeepTheirOwnPortions() throws Exception {
        List<NobelPrizeAward.Achievement> achievements =
                award("physics-2018", "phy").achievements();

        assertEquals("for the optical tweezers and their application to biological systems",
                achievements.getFirst().motivation());
        assertEquals(List.of("Arthur Ashkin"), names(achievements.getFirst()));
        assertEquals(List.of("1/2"), portions(achievements.getFirst()));

        assertEquals("for their method of generating high-intensity, ultra-short optical pulses",
                achievements.get(1).motivation());
        assertEquals(2, names(achievements.get(1)).size(), "the achievement has two laureates");
        assertEquals(List.of("1/4", "1/4"), portions(achievements.get(1)),
                "each laureate's allocation survives instead of becoming one share value");
        assertTrue(names(achievements.get(1)).contains("Donna Strickland"));
    }

    @Test void everyLaureateSurvivesTheGroupingInSourceOrder() throws Exception {
        NobelPrizeAward physics = award("physics-2018", "phy");

        assertEquals(List.of("960", "961", "962"),
                physics.laureateAwards().stream()
                        .map(NobelPrizeAward.LaureateAward::apiId).toList(),
                "the ids that join to Wikidata P8024, in sortOrder");
        assertEquals(List.of(1, 2, 3),
                physics.laureateAwards().stream()
                        .map(NobelPrizeAward.LaureateAward::sortOrder).toList());
        assertFalse(physics.laureateAwards().getFirst().organization());
    }

    @Test void anOrganisationIsNamedFromTheFieldTheSourceUsesForOne() throws Exception {
        NobelPrizeAward peace = award("peace-2020", "pea");

        NobelPrizeAward.LaureateAward wfp = peace.laureateAwards().getFirst();
        assertEquals("pea", peace.categoryCode());
        assertEquals("World Food Programme", wfp.name(),
                "an organisation has orgName where a person has knownName");
        assertTrue(wfp.organization(), "the Peace Prize is not always won by a person");
        assertEquals("994", wfp.apiId());
        assertEquals("1", wfp.portion(), "an undivided prize");
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
        assertEquals("phy", awards.getFirst().categoryCode());

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> client.category("chemistry"));
        assertTrue(refused.getMessage().contains("chemistry"),
                "the refusal names what was asked for");
    }

    @Test void aResponseWithNoPrizesYieldsNothingRatherThanFailing() throws Exception {
        assertEquals(List.of(), NobelApiClient.parse(
                new ObjectMapper().readTree("{\"nobelPrizes\":[]}"), "phy"));
        assertEquals(List.of(), NobelApiClient.parse(null, "phy"));
    }

    private static List<String> names(NobelPrizeAward.Achievement achievement) {
        return achievement.laureateAwards().stream()
                .map(NobelPrizeAward.LaureateAward::name).toList();
    }

    private static List<String> portions(NobelPrizeAward.Achievement achievement) {
        return achievement.laureateAwards().stream()
                .map(NobelPrizeAward.LaureateAward::portion).toList();
    }
}
