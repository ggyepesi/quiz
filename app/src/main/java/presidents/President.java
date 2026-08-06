package presidents;

import java.util.ArrayList;
import objectview.annotations.DisplayField;
import java.util.List;

import aux.FlexibleDate;
import objectview.media.ImagePane;
import objectview.ViewableAdapter;
import quiz.source.ManualEntity;

// private fields are seemengly unused - they are used via reflection!
@SuppressWarnings("unused")
public class President extends ManualEntity {
    private Person person;
    private List<Term> terms = new ArrayList<>();
    
    public President() {}

    public President(Person person) {
        this.person = person;
    }

    public void addTerm(Term term) {
        terms.add(term);
    }

    // person can be null if this instance is a projection!
    @Override
    public String getIdentifier() { return person == null ? "" : person.getIdentifier(); }

    @Override
    public String getDisplayName() { return person == null ? "" : person.getDisplayName(); }
}

@SuppressWarnings("unused")
class Term extends ViewableAdapter {
    private int number = 0;
    private String party;

    private FlexibleDate start;
    private FlexibleDate end;
    
    private Person previous;
    private Person next;

    public Term(FlexibleDate start, FlexibleDate end) {
        this.start = start;
        this.end = end;
    }
    
    private Term() {}

    public void setNUmber(int number) {
        this.number = number;
    }

    public void setParty(String party) {
        this.party = party;
    }

    @Override
    public String getIdentifier() { return number + ", " + party + ", " + start + "-" + end; }

    // Terse label for the chip -- just the span of years; the full identifier
    // (number, party, exact dates) is still shown in the expanded fields.
    @Override
    public String getDisplayName() {
        String from = start == null ? "?" : String.valueOf(start.getYear());
        String to = end == null ? "?" : String.valueOf(end.getYear());
        return from + "–" + to;
    }

}

enum CauseOfDeath {
    NATURAL,
    ASSASSINATION
}

@SuppressWarnings("unused")
class Person extends ManualEntity {
    @DisplayField private String name;

    private ImagePane portrait;
    private FlexibleDate born;
    private FlexibleDate died;

    private CauseOfDeath causeOfDeath;

    private Person() {}

    public Person(String name) {
        this.name = name;
    }

    public void setPortrait(ImagePane portrait) {
        this.portrait = portrait;
    }

    public void setBorn(FlexibleDate born) {
        this.born = born;
    }

    public void setDied(FlexibleDate died) {
        this.died = died;
    }

    @Override
    public String getIdentifier() { return name; }

    @Override
    public String getDisplayName() { return name; }

    public String toString() {
        return name + ", born " + born + ", died " + died;
    }
}

class Party extends ManualEntity {
    @SuppressWarnings("unused")
    private String name;

    public Party(String name) {
        this.name = name;
    }

    @Override
    public String getIdentifier() { return name; }

    @Override
    public String getDisplayName() { return name; }

}
