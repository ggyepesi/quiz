package nobel;

import quiz.ui.ImagePane;
import quiz.QuizableAdapter;

import java.util.ArrayList;
import java.util.List;

public
class LaureatesWithMotivation extends QuizableAdapter {
    private NobelPrize prize;
    private final List<Laureate> laureates = new ArrayList<>();
    private MotivationParser.Motivation motivation;

    public NobelPrize getPrize() {
        return prize;
    }

    public void setPrize(NobelPrize prize) {
        this.prize = prize;
    }

    public MotivationParser.Motivation getMotivation() {
        return motivation;
    }

    public void setMotivation(String motivation) {
        this.motivation = MotivationParser.parse(motivation);
    }

    public List<Laureate> getLaureates() {
        return laureates;
    }

    @Override
    public String getName() {
        return "";
    }

    @Override
    public QuizableAdapter createNew() {
        return new LaureatesWithMotivation();
    }
}

class Laureate extends QuizableAdapter {
    private String name;
    private ImagePane portrait;

    public ImagePane getPortrait() {
        return portrait;
    }

    public void setPortrait(ImagePane portrait) {
        this.portrait = portrait;
    }

    public Laureate(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public QuizableAdapter createNew() {
        return new Laureate();
    }

    private Laureate() {}
}

