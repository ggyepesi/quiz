package quiz.model;

import java.util.List;

public class QuizState {
    private final List<Object> question;
    private List<Object> selectedAnswer;
    private boolean correct;
    private boolean done;

    public QuizState(List<Object> question) {
        this.question = question;
    }

    public List<Object> getQuestion() { return question; }
    public List<Object> getSelectedAnswer() { return selectedAnswer; }
    public void setSelectedAnswer(List<Object> selectedAnswer) { this.selectedAnswer = selectedAnswer; }

    public boolean isCorrect() { return correct; }
    public void setCorrect(boolean correct) { this.correct = correct; }

    public boolean isDone() { return done; }
    public void setDone(boolean done) { this.done = done; }
}
