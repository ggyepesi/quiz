package quiz.enrichment;

import org.junit.jupiter.api.Test;
import wikidata.explore.query.core.QueryContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WikimediaEntityLookupTest {

    @Test
    void parsesNeutralTypedClaimsSitelinksAndQualifiers()
            throws Exception {
        String json = """
                {
                  "entities": {
                    "Q42970": {
                      "labels": {"en": {"value": "Amnesty International"}},
                      "descriptions": {"en": {"value": "human-rights organization"}},
                      "aliases": {"en": [
                        {"value": "AI"}
                      ]},
                      "sitelinks": {
                        "enwiki": {"title": "Amnesty International"}
                      },
                      "claims": {
                        "P154": [{
                          "rank": "preferred",
                          "mainsnak": {
                            "datatype": "commonsMedia",
                            "datavalue": {
                              "value": "Amnesty international Logo.svg"
                            }
                          },
                          "qualifiers": {
                            "P582": [{
                              "datatype": "time",
                              "datavalue": {
                                "value": {
                                  "time": "+2010-00-00T00:00:00Z",
                                  "precision": 9
                                }
                              }
                            }]
                          }
                        }]
                      }
                    }
                  }
                }
                """;
        WikimediaEntityLookup lookup =
                new WikimediaEntityLookup(uri -> json);

        WikimediaEntityLookup.EntityRecord entity =
                lookup.byQid("Q42970").execute(new QueryContext(null, null));

        assertEquals("Amnesty International", entity.label());
        assertEquals("AI", entity.aliases().getFirst());
        assertEquals("Amnesty International", entity.sitelink("enwiki"));
        WikimediaEntityLookup.Claim logo = entity.claims("P154").getFirst();
        assertEquals("preferred", logo.rank());
        assertEquals("commonsMedia", logo.value().datatype());
        assertEquals("Amnesty international Logo.svg",
                logo.value().stringValue());
        assertFalse(logo.deprecated());
        assertEquals("time",
                logo.qualifiers().get("P582").getFirst().datatype());
    }
}
