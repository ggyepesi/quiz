package quiz;

import quiz.model.QuizState;
import quiz.model.QuizMode;
import java.util.*;
import java.util.function.Consumer;

/** Minimal controller to sequence questions. */
public class QuizController {

    private final QuizMode mode;
    private final Map<String, ? extends Quizable> quizables;
    private final Consumer<QuizState> onRoundReady;

    public QuizController(QuizMode mode,
                          Map<String, ? extends Quizable> quizables,
                          Consumer<QuizState> onRoundReady) {
        this.mode = mode;
        this.quizables = quizables;
        this.onRoundReady = onRoundReady;
    }

    public void start(List<List<Object>> queryKeys) {
        Collections.shuffle(queryKeys, new Random());
        for (List<Object> q : queryKeys) {
            onRoundReady.accept(new QuizState(q));
        }
    }

    public QuizMode getMode() { return mode; }
}
