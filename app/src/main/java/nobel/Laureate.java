package nobel;

import objectview.media.ImagePane;
import quiz.QuizableAdapter;

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

