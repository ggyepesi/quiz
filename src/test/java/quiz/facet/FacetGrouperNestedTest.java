package quiz.facet;

import objectview.facet.Facet;
import objectview.facet.FacetGrouper;
import org.junit.jupiter.api.Test;
import quiz.QuizableGroup;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FacetGrouperNestedTest {

    private static WikidataDynamicObject nom(String qid, String cat, String year) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, qid);
        o.put("category", cat);
        o.put("year", year);
        return o;
    }

    @Test void facetsNestInDeclaredOrder() {
        WikidataDynamicObject n1 = nom("n1", "Best Actress", "1985");
        WikidataDynamicObject n2 = nom("n2", "Best Actress", "1986");
        WikidataDynamicObject n3 = nom("n3", "Best Picture", "1985");
        List<WikidataDynamicObject> all = List.of(n1, n2, n3);

        Facet byCategory = Facet.derived("by category",
                q -> List.of(String.valueOf(((WikidataDynamicObject) q).get("category"))));
        Facet byYear = Facet.derived("by year",
                q -> List.of(String.valueOf(((WikidataDynamicObject) q).get("year"))));

        QuizableGroup root = FacetGrouper.groupNested(QuizableGroup::new, 
                "All Nomination", all, List.of(byCategory, byYear));

        // Universe holds everything.
        assertEquals(QuizableGroup.Role.UNIVERSE, root.getRole());
        assertEquals(3, root.getMembers().size());

        // First level: by category (a FACET dimension), category buckets below it.
        QuizableGroup byCat = root.getChild("by category");
        assertNotNull(byCat, "top dimension is the FIRST declared facet");
        assertEquals(QuizableGroup.Role.FACET, byCat.getRole());

        QuizableGroup bestActress = byCat.getChild("Best Actress");
        assertNotNull(bestActress);
        assertEquals(QuizableGroup.Role.BUCKET, bestActress.getRole());
        assertEquals(2, bestActress.getMembers().size(), "both Best Actress noms");

        // Second level nests UNDER the category bucket: by year → year buckets.
        QuizableGroup byYearUnderActress = bestActress.getChild("by year");
        assertNotNull(byYearUnderActress, "year drills down inside the category");
        assertEquals(QuizableGroup.Role.FACET, byYearUnderActress.getRole());

        QuizableGroup y1985 = byYearUnderActress.getChild("1985");
        assertNotNull(y1985);
        assertEquals(1, y1985.getMembers().size(), "only n1 is Best Actress 1985");

        // The other category partitions independently and only sees its own member.
        QuizableGroup bestPicture = byCat.getChild("Best Picture");
        assertEquals(1, bestPicture.getMembers().size());
        assertEquals(1, bestPicture.getChild("by year").getChild("1985")
                .getMembers().size());
        assertEquals(1, bestPicture.getChild("by year").getChildren().size(),
                "Best Picture only has the 1985 sub-bucket");
    }
}
