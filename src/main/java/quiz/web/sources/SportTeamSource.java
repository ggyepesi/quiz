package quiz.web.sources;

import flag.SportTeams;
import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.web.QuizableSource;

import java.util.Collection;

/**
 * Sport teams (NBA/NFL/NHL/MLB) read from bundled resource files — no
 * network. Each team carries a logo ({@code ImagePane}) served as PNG via
 * the image endpoint, and a league/group hierarchy for scoping quizzes.
 *
 * <p>Note: {@code SportTeams.buildViews()} also builds Swing components, so
 * this loads under a display (fine for the local dev server).
 */
public class SportTeamSource implements QuizableSource {

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
        return teams().getGroupView().getRootGroup();
    }
}
