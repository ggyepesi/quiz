package flag;

import quiz.ui.ImagePane;
import quiz.QuizableAdapter;

// seemingly unused fields are used via reflection
public class SportTeam extends QuizableAdapter {
    private String name = null;
    @SuppressWarnings("unused")
    private String league = null;
    @SuppressWarnings("unused")
    private ImagePane logo = null;
    @SuppressWarnings("unused")
    private String state = null;
    @SuppressWarnings("unused")
    private String capital = null;
    @SuppressWarnings("unused")
    private String stadium = null;

    public SportTeam(String name) {
        this.name = name;
    }

    public void setLeague(String league) {
        this.league = league;
    }

    public void setLogo(ImagePane logo) {
        this.logo = logo;
    }
 
    public void setState(String state) {
        this.state = state;
    }

    public void setCapital(String capital) {
        this.capital = capital;
    }

    public void setStadium(String stadium) {
        this.stadium = stadium;
    }

    @Override
    public String getIdentifier() { return name; }

    @Override
    public String getDisplayName() { return name; }

    @Override
    public QuizableAdapter createNew() {
        return new SportTeam("");
    }
}


