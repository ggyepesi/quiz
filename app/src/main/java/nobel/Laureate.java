package nobel;

import objectview.media.ImagePane;
import quiz.source.ManualEntity;

class Laureate extends ManualEntity {
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

    private Laureate() {}
}
