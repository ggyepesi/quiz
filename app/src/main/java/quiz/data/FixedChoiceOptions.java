package quiz.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;

/** One fixed-choice selection rule for desktop and web quizzes. Rendering receives
 * the final list and may decorate it, but never removes choices afterwards. */
public final class FixedChoiceOptions {
    private FixedChoiceOptions() {}

    public static <T> List<T> choose(
            List<T> correctAnswers,
            Collection<T> excludedDistractors,
            Collection<T> candidates,
            int count,
            Random random) {
        if (correctAnswers == null || correctAnswers.isEmpty()
                || candidates == null || count <= 0) {
            return List.of();
        }
        Random source = random == null ? new Random() : random;
        LinkedHashSet<T> chosen = new LinkedHashSet<>();
        chosen.add(correctAnswers.get(source.nextInt(correctAnswers.size())));

        // Reservoir-sample only the distractors the round needs. Copying and
        // shuffling a six-figure candidate pool for every four-card round made
        // fixed-choice rendering proportional in memory to the whole domain.
        int distractorCount = Math.max(0, count - chosen.size());
        List<T> distractors = new ArrayList<>(distractorCount);
        long eligible = 0;
        for (T candidate : candidates) {
            if (excludedDistractors != null
                    && excludedDistractors.contains(candidate)) continue;
            if (chosen.contains(candidate) || distractors.contains(candidate)) continue;
            eligible++;
            if (distractors.size() < distractorCount) {
                distractors.add(candidate);
                continue;
            }
            long replacement = source.nextLong(eligible);
            if (replacement < distractorCount) {
                distractors.set((int) replacement, candidate);
            }
        }
        chosen.addAll(distractors);

        List<T> result = new ArrayList<>(chosen);
        Collections.shuffle(result, source);
        return List.copyOf(result);
    }
}
