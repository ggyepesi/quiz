package quiz.web.sources;

import flag.SportTeams;
import quiz.Quizable;
import quiz.web.QuizableSource;

import java.util.Collection;

/**
 * Sport teams (NBA/NFL/NHL/MLB) read from bundled resource files — no
 * network. Each team carries a logo ({@code ImagePane}) served as PNG via
 * the image endpoint.
 *
 * <p>Note: {@code SportTeams.buildViews()} also builds Swing components, so
 * this loads under a display (fine for the local dev server).
 */
public class SportTeamSource implements QuizableSource {

    @Override
    public String type() {
        return "SportTeam";
    }

    @Override
    public Collection<? extends Quizable> load() throws Exception {
        SportTeams teams = new SportTeams();
        teams.buildViews();
        return teams.getQuizables().values();
    }
}
