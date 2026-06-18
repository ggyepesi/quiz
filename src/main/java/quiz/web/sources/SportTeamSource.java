package quiz.web.sources;

import flag.SportTeams;
import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.facet.Facet;
import quiz.facet.FacetGrouper;
import quiz.web.QuizableSource;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Sport teams (NBA/NFL/NHL/MLB) read from bundled resource files — no
 * network. Each team carries a logo ({@code ImagePane}) served as PNG via
 * the image endpoint, and a faceted group hierarchy for scoping quizzes.
 *
 * <p>Note: {@code SportTeams.buildViews()} also builds Swing components, so
 * this loads under a display (fine for the local dev server).
 *
 * <p>The group tree is built declaratively from {@link Facet}s rather than the
 * Swing-side hand-built tree, so it carries explicit
 * {@link QuizableGroup.Role}s (facet nodes vs. selectable buckets).
 */
public class SportTeamSource implements QuizableSource {

    /** Provinces — a team's state value in this set means Canada, else USA. */
    private static final Set<String> CANADA =
            Set.of("Alberta", "British Columbia", "Manitoba", "Ontario", "Quebec");

    private static final List<Facet> FACETS = List.of(
            Facet.field("league", "League"),
            Facet.mapped("Country", "state", s -> CANADA.contains(s) ? "Canada" : "USA"),
            Facet.field("state", "State"),
            Facet.field("capital", "City"),
            Facet.field("stadium", "Stadium"));

    private SportTeams teams;

    private synchronized SportTeams teams() throws Exception {
        if (teams == null) {
            SportTeams t = new SportTeams();
            t.buildViews();
            teams = t;
        }
        return teams;
    }

    @Override
    public String type() {
        return "SportTeam";
    }

    @Override
    public Collection<? extends Quizable> load() throws Exception {
        return teams().getQuizables().values();
    }

    @Override
    public QuizableGroup rootGroup() throws Exception {
        return FacetGrouper.group("All teams", load(), FACETS);
    }
}
