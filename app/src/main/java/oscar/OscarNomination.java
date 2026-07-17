package oscar;

import objectview.annotations.Reference;
import quiz.QuizableAdapter;
import wikidata.explore.extract.WikidataDynamicObject;

public class OscarNomination extends QuizableAdapter {
    private String name;

    @Reference
    private WikidataDynamicObject nominee;
    @Reference
    private WikidataDynamicObject award;
    @Reference
    private WikidataDynamicObject work;

    public int ceremonyYear;
    public int filmYear;
    public boolean winner;

    public OscarNomination() {}

    public WikidataDynamicObject getNominee() {
        return nominee;
    }

    public WikidataDynamicObject getAward() {
        return award;
    }

    public WikidataDynamicObject getWork() {
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

    public void setNominee(WikidataDynamicObject nominee) {
        this.nominee = nominee;
    }

    public void setAward(WikidataDynamicObject award) {
        this.award = award;
    }

    public void setWork(WikidataDynamicObject work) {
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
        StringBuilder s = new StringBuilder();

        if (ceremonyYear > 0) {
            s.append(ceremonyYear).append(" · ");
        }

        s.append(award == null ? "Award" : award.getName());

        String who = nominee == null ? null : nominee.getName();
        if (who != null && !who.isBlank()) {
            s.append(" — ").append(who);
        }

        if (winner) {
            s.append(" [winner]");
        }

        name = s.toString().trim();
        return name;
    }

    @Override
    public String getIdentifier() { return name == null ? setName() : name; }

    @Override
    public String getDisplayName() { return getIdentifier(); }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}