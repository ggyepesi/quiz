package presidents;

import java.util.ArrayList;
import java.util.List;

import aux.FlexibleDate;
import quiz.ui.ImagePane;
import quiz.QuizableAdapter;

// private fields are seemengly unused - they are used via reflection!
@SuppressWarnings("unused")
public class President extends QuizableAdapter {
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
    public String getName() {
        return person == null ? "" : person.getName();
    }

    @Override
    public QuizableAdapter createNew() {
        return new President();
    }
}

@SuppressWarnings("unused")
class Term extends QuizableAdapter {
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
    public String getName() {
        return number + ", " + party + ", " + start + "-" + end;
    }

    @Override
    public QuizableAdapter createNew() {
        return new Term();
    }
}

enum CauseOfDeath {
    NATURAL,
    ASSASSINATION
}

@SuppressWarnings("unused")
class Person extends QuizableAdapter {
    private String name;

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
    public String getName() {
        return name;
    }

    @Override
    public QuizableAdapter createNew() {
        return new Person();
    }

    public String toString() {
        return name + ", born " + born + ", died " + died;
    }
}

class Party extends QuizableAdapter {
    @SuppressWarnings("unused")
    private String name;

    public Party(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public QuizableAdapter createNew() {
        return new Party("");
    }
}