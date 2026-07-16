package objectview.viewconfig;

import quiz.Quizable;
import objectview.ImagePane;

import java.util.Collection;
import java.util.Map;

public final class QuizablePanelConfigAdapter {
    private QuizablePanelConfigAdapter() {}

    public static QuizablePanelConfig fromOldArgs(Quizable q,
                                                  boolean showNames,
                                                  boolean showImages,
                                                  boolean expand) {

        if (q == null) {
            return QuizablePanelConfig.leaf();
        }

        Class<? extends Quizable> cls = q.getClass();

        if (expand) {
            return QuizablePanelConfig.all(cls)
                    .setAddListener(true)
                    .setThumb(showImages);
        }

        return QuizablePanelConfig.of(cls)
                .setAddListener(true)
                .setThumb(showImages);
    }

    public static QuizablePanelConfig defaultConfigForValue(Object value) {
        if (value == null) {
            return QuizablePanelConfig.leaf();
        }

        if (value instanceof ImagePane) {
            return QuizablePanelConfig.leaf();
        }

        if (value instanceof Quizable q) {
            // IMPORTANT: compact by default, do not recurse here.
            return nameOnlyConfig(q.getClass());
        }

        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Quizable q) {
                    return QuizablePanelConfig.all(q.getClass());
                }
            }
            return QuizablePanelConfig.leaf();
        }

        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (item instanceof Quizable q) {
                    return QuizablePanelConfig.all(q.getClass());
                }
            }
            return QuizablePanelConfig.leaf();
        }

        return QuizablePanelConfig.leaf();
    }

    public static QuizablePanelConfig defaultConfigForValueType(Class<?> type) {
        if (type == null) {
            return QuizablePanelConfig.leaf();
        }

        if (ImagePane.class.isAssignableFrom(type)) {
            return QuizablePanelConfig.leaf();
        }

        if (Quizable.class.isAssignableFrom(type)) {
            @SuppressWarnings("unchecked")
            Class<? extends Quizable> qCls = (Class<? extends Quizable>) type;
            return nameOnlyConfig(qCls);
        }

        return QuizablePanelConfig.leaf();
    }

    private static QuizablePanelConfig nameOnlyConfig(Class<? extends Quizable> cls) {
        return QuizablePanelConfig.of(cls)
                .setAddListener(true)
                .setThumb(true);
    }
}