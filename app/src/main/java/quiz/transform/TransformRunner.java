package quiz.transform;

import java.util.ArrayList;
import java.util.List;

public class TransformRunner {

    private final List<ClassTransformPlan<?, ?>> plans = new ArrayList<>();

    public TransformRunner add(ClassTransformPlan<?, ?> plan) {
        plans.add(plan);
        return this;
    }

    public TransformContext run(Iterable<?> sources) {
        TransformContext context = new TransformContext();

        for (Object source : sources) {
            for (ClassTransformPlan<?, ?> plan : plans) {
                plan.applyIfMatches(source, context);
            }
        }

        return context;
    }
}