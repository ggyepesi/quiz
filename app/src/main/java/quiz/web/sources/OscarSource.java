package quiz.web.sources;

import oscar.OscarNomination;
import oscar.OscarWikidataReader;
import objectview.Viewable;
import quiz.web.ViewableSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Oscar winners loaded headlessly from Wikidata (SPARQL). To keep the first
 * request snappy this loads only the winners of the first few award
 * categories; widen {@link #MAX_AWARDS} for the full set. The {@link
 * quiz.web.ViewableStore} caches the result after the first load.
 */
public class OscarSource implements ViewableSource {

    private static final int MAX_AWARDS = 8;
    private static final int PER_AWARD = 100;

    @Override
    public String type() {
        return "OscarNomination";
    }

    @Override
    public Collection<? extends Viewable> load() throws Exception {
        OscarWikidataReader reader = new OscarWikidataReader();
        List<OscarWikidataReader.AwardItem> awards = reader.readOscarAwards();

        List<OscarNomination> out = new ArrayList<>();
        int n = Math.min(awards.size(), MAX_AWARDS);

        for (int i = 0; i < n; i++) {
            out.addAll(reader.readWinnerPageForAward(awards.get(i).qid(), PER_AWARD, 0));
        }

        return out;
    }
}
