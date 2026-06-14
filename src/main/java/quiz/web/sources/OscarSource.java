package quiz.web.sources;

import oscar.OscarNomination;
import oscar.OscarWikidataReader;
import quiz.Quizable;
import quiz.web.QuizableSource;

import java.util.Collection;

/**
 * Oscar winners loaded headlessly from Wikidata (SPARQL). First access is a
 * network fetch; the {@link quiz.web.QuizableStore} caches the result.
 */
public class OscarSource implements QuizableSource {

    @Override
    public String type() {
        return "OscarNomination";
    }

    @Override
    public Collection<? extends Quizable> load() throws Exception {
        return new OscarWikidataReader().readAllWinners();
    }
}
