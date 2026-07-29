package quiz.graph;

import objectview.Viewable;

public class ViewableEdge {
    private final Viewable from;
    private final Viewable to;
    private final String field;

    public ViewableEdge(Viewable from, Viewable to, String field) {
        this.from = from;
        this.to = to;
        this.field = field;
    }

    public Viewable getFrom() { return from; }
    public Viewable getTo() { return to; }
    public String getField() { return field; }

    @Override
    public String toString() {
        return field;
    }
}