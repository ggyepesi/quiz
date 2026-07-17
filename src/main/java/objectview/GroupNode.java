package objectview;

import quiz.QuizableGroup;

public record GroupNode(QuizableGroup group) {
    public String getName() {
        return group.getName();
    }

    public String getFullName() {
        return group.getFullName();
    }

    @Override
    public String toString() {
        return group.getName();
    }
}
