package nobel;
import java.util.ArrayList;
import java.util.List;

import quiz.QuizableAdapter;

public class NobelPrize extends QuizableAdapter {
    public static enum Domain {
         PHYSICS,
         CHEMISTRY,
         PHYSIOLOGY_OR_MEDICINE,
         LITERATURE,
         PEACE,
         ECONOMICS,
         NONE
    }
    
    private String name = "";
    private int year;
    private Domain domain;
    private final List<LaureatesWithMotivation> laureatesWithMotivation = new ArrayList<>();

    public Domain getDomain() {
        return domain;
    }

    public void setDomain(Domain domain) {
        this.domain = domain;
        name = year + " " + domain;
    }

    public int getYear() {
        return year;
    }

    public void setYear(int year) {
        this.year = year;
        name = year + " " + domain;
    }

    public List<LaureatesWithMotivation> getLaureatesWithMotivation() {
        return laureatesWithMotivation;
    }

    @Override
    public String getIdentifier() { return name; }

    @Override
    public String getDisplayName() { return name; }
}
