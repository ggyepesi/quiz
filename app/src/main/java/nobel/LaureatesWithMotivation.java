package nobel;

import objectview.media.ImagePane;
import quiz.QuizableAdapter;

import java.util.ArrayList;
import java.util.List;

public
class LaureatesWithMotivation extends QuizableAdapter {
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

    /** A grouping wrapper has no natural name — its laureates ARE its content. It needs
     *  a STABLE, content-derived identity ONLY so the snapshot store can key/reference
     *  it (a blank id is silently dropped, which nulled the whole chain). The display
     *  name stays empty by design — the wrapper is shown by its contents, not a
     *  redundant long label. */
    @Override
    public String getIdentifier() {
        return Integer.toHexString(laureateNames().hashCode());
    }

    @Override
    public String getDisplayName() { return ""; }

    private String laureateNames() {
        return laureates.stream()
                .map(Laureate::getDisplayName)
                .filter(n -> n != null && !n.isBlank())
                .collect(java.util.stream.Collectors.joining(", "));
    }
}

