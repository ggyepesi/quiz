package objectview.viewconfig;

import objectview.Viewable;
import objectview.ImagePane;

import java.util.Collection;
import java.util.Map;

public final class ViewablePanelConfigAdapter {
    private ViewablePanelConfigAdapter() {}

    public static ViewablePanelConfig fromOldArgs(Viewable q,
                                                  boolean showNames,
                                                  boolean showImages,
                                                  boolean expand) {

        if (q == null) {
            return ViewablePanelConfig.leaf();
        }

        Class<? extends Viewable> cls = q.getClass();

        if (expand) {
            return ViewablePanelConfig.all(cls)
                                      .setAddListener(true)
                                      .setThumb(showImages);
        }

        return ViewablePanelConfig.of(cls)
                                  .setAddListener(true)
                                  .setThumb(showImages);
    }

    public static ViewablePanelConfig defaultConfigForValue(Object value) {
        if (value == null) {
            return ViewablePanelConfig.leaf();
        }

        if (value instanceof ImagePane) {
            return ViewablePanelConfig.leaf();
        }

        if (value instanceof Viewable q) {
            // IMPORTANT: compact by default, do not recurse here.
            return nameOnlyConfig(q.getClass());
        }

        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Viewable q) {
                    return ViewablePanelConfig.all(q.getClass());
                }
            }
            return ViewablePanelConfig.leaf();
        }

        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (item instanceof Viewable q) {
                    return ViewablePanelConfig.all(q.getClass());
                }
            }
            return ViewablePanelConfig.leaf();
        }

        return ViewablePanelConfig.leaf();
    }

    public static ViewablePanelConfig defaultConfigForValueType(Class<?> type) {
        if (type == null) {
            return ViewablePanelConfig.leaf();
        }

        if (ImagePane.class.isAssignableFrom(type)) {
            return ViewablePanelConfig.leaf();
        }

        if (Viewable.class.isAssignableFrom(type)) {
            @SuppressWarnings("unchecked")
            Class<? extends Viewable> qCls = (Class<? extends Viewable>) type;
            return nameOnlyConfig(qCls);
        }

        return ViewablePanelConfig.leaf();
    }

    private static ViewablePanelConfig nameOnlyConfig(Class<? extends Viewable> cls) {
        return ViewablePanelConfig.of(cls)
                                  .setAddListener(true)
                                  .setThumb(true);
    }
}