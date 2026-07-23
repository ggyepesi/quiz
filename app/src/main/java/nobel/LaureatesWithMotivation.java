package nobel;

import objectview.media.ImagePane;
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

    /** The shared laureates ARE the identity/label of this group — otherwise it renders
     *  as a blank ref (empty name in the transform card and on the web). */
    private String laureateNames() {
        return laureates.stream()
                .map(Laureate::getDisplayName)
                .filter(n -> n != null && !n.isBlank())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    @Override
    public String getIdentifier() { return laureateNames(); }

    @Override
    public String getDisplayName() { return laureateNames(); }
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
    public String getIdentifier() { return name; }

    @Override
    public String getDisplayName() { return name; }

    @Override
    public QuizableAdapter createNew() {
        return new Laureate();
    }

    private Laureate() {}
}

