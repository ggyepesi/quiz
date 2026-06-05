package quiz.graph;

import quiz.Quizable;

public class QuizableEdge {
    private final Quizable from;
    private final Quizable to;
    private final String field;

    public QuizableEdge(Quizable from, Quizable to, String field) {
        this.from = from;
        this.to = to;
        this.field = field;
    }

    public Quizable getFrom() { return from; }
    public Quizable getTo() { return to; }
    public String getField() { return field; }

    @Override
    public String toString() {
        return field;
    }
}