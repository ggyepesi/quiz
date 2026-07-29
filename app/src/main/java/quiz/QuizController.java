package quiz;

import objectview.Viewable;
import quiz.model.QuizState;
import quiz.model.QuizMode;
import java.util.*;
import java.util.function.Consumer;

/** Minimal controller to sequence questions. */
public class QuizController {

    private final QuizMode mode;
    private final Map<String, ? extends Viewable> viewables;
    private final Consumer<QuizState> onRoundReady;

    public QuizController(QuizMode mode,
                          Map<String, ? extends Viewable> viewables,
                          Consumer<QuizState> onRoundReady) {
        this.mode = mode;
        this.viewables = viewables;
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
