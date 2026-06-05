package oscar;

import quiz.QuizableAdapter;
import quiz.QuizableReference;
import wikidata.WikidataEntity;

public class OscarNomination extends QuizableAdapter {
    private String name;

    @QuizableReference
    private WikidataEntity nominee;
    @QuizableReference
    private WikidataEntity award;
    @QuizableReference
    private WikidataEntity work;

    public int ceremonyYear;
    public int filmYear;
    public boolean winner;

    public OscarNomination() {}

    public WikidataEntity getNominee() {
        return nominee;
    }

    public WikidataEntity getAward() {
        return award;
    }

    public WikidataEntity getWork() {
        return work;
    }

    public int getCeremonyYear() {
        return ceremonyYear;
    }

    public int getFilmYear() {
        return filmYear;
    }

    public boolean isWinner() {
        return winner;
    }

    public void setNominee(WikidataEntity nominee) {
        this.nominee = nominee;
    }

    public void setAward(WikidataEntity award) {
        this.award = award;
    }

    public void setWork(WikidataEntity work) {
        this.work = work;
    }

    public void setCeremonyYear(int ceremonyYear) {
        this.ceremonyYear = ceremonyYear;
    }

    public void setFilmYear(int filmYear) {
        this.filmYear = filmYear;
    }

    public void setWinner(boolean winner) {
        this.winner = winner;
    }

    public String setName() {
        String s = ceremonyYear + " " + (award == null ? "NO AWARD" : award.getName()) + " " +
                (nominee == null ? "NO NOMINEE" : nominee.getName());
        if (winner) {
            s += " [winner]";
        }
        name = s.trim();
        return name;
    }

    @Override
    public String getName() {
        return name == null ? setName() : name;
    }

    @Override
    public QuizableAdapter createNew() {
        return new OscarNomination();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override
    public String toString() {
        return getName();
    }
}