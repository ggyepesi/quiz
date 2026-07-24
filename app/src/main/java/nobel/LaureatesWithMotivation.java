package nobel;

import objectview.media.ImagePane;
import quiz.QuizableAdapter;

import java.util.ArrayList;
import java.util.List;

public
class LaureatesWithMotivation extends QuizableAdapter implements quiz.ValueObject {
    private final List<Laureate> laureates = new ArrayList<>();
    private MotivationParser.Motivation motivation;

    public MotivationParser.Motivation getMotivation() {
        return motivation;
    }

    public void setMotivation(String motivation) {
        this.motivation = MotivationParser.parse(motivation);
    }

    public List<Laureate> getLaureates() {
        return laureates;
    }

    /** A VALUE object ({@link quiz.ValueObject}) — inlined in its NobelPrize, no identity
     *  invented. The laureate names are just its LABEL (shown as the inline section
     *  heading), not an identifier. */
    @Override
    public String getIdentifier() { return laureateNames(); }

    @Override
    public String getDisplayName() { return laureateNames(); }

    private String laureateNames() {
        return laureates.stream()
                .map(Laureate::getDisplayName)
                .filter(n -> n != null && !n.isBlank())
                .collect(java.util.stream.Collectors.joining(", "));
    }
}

