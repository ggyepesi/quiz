package objectview.viewconfig;

import objectview.Viewable;
import objectview.ViewableAdapter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ViewablePanelConfig {

    // Explicitly configured fields. These are shown regardless of minor/non-minor.
    private final Map<String, ViewablePanelConfig> fields = new LinkedHashMap<>();
    private Class<? extends Viewable> cls;

    private transient final Map<Class<?>, List<Field>> visibleFieldsCache =
            new ConcurrentHashMap<>();

    // Means: include all non-minor fields by default.
    private boolean allFields = true;

    // Means: include all @MinorField fields by default too.
    private boolean allMinorFields = false;

    private boolean addListener = true;
    private boolean thumb = false;
    // Render images with their answer text blurred out (quiz query panels).
    private boolean blurImages = false;
    private AnswerType answerType = AnswerType.AUTO;

    public static ViewablePanelConfig of(Class<? extends Viewable> cls) {
        ViewablePanelConfig c = new ViewablePanelConfig();
        c.cls = cls;
        c.allFields = true;
        return c;
    }

    public static ViewablePanelConfig all(Class<? extends Viewable> cls) {
        return of(cls).initializeAllFields(true);
    }

    public static ViewablePanelConfig allWithMinorFields(Class<? extends Viewable> cls) {
        return of(cls).setAllMinorFields(true).initializeAllFields(true);
    }

    public static ViewablePanelConfig leaf() {
        ViewablePanelConfig cfg = new ViewablePanelConfig();
        cfg.setAllFields(false);
        cfg.setAllMinorFields(false);
        return cfg;
    }

    public ViewablePanelConfig copy() {
        ViewablePanelConfig c = new ViewablePanelConfig();

        c.cls = this.cls;
        c.allFields = this.allFields;
        c.allMinorFields = this.allMinorFields;
        c.addListener = this.addListener;
        c.thumb = this.thumb;
        c.blurImages = this.blurImages;
        c.answerType = this.answerType;

        for (Map.Entry<String, ViewablePanelConfig> e : fields.entrySet()) {
            c.fields.put(e.getKey(), e.getValue().copy());
        }

        return c;
    }

    public ViewablePanelConfig initializeAllFields(boolean force) {
        if (cls == null) {
            return this;
        }

        if (!allFields && !force) {
            return this;
        }

        for (Field f : ViewableAdapter.getAllFields(cls)) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }

            if (ViewableAdapter.isMinorField(f) && !allMinorFields) {
                continue;
            }

            if (fields.containsKey(f.getName())) {
                continue;
            }

            fields.put(f.getName(), defaultChildConfigForField(f));
        }

        return this;
    }

    public void clearCache() {
        visibleFieldsCache.clear();

        for (ViewablePanelConfig child : fields.values()) {
            if (child != null) {
                child.clearCache();
            }
        }
    }

    private ViewablePanelConfig defaultChildConfigForField(Field f) {
        if (Viewable.class.isAssignableFrom(f.getType())) {
            @SuppressWarnings("unchecked") Class<? extends Viewable> sub = (Class<? extends Viewable>) f.getType();

            return ViewablePanelConfig.of(sub);
        }

        return new ViewablePanelConfig();
    }

    /**
     * Central display rule:
     * <p>
     * - explicitly configured field: show
     * - normal field + allFields: show
     * - minor field + allMinorFields: show
     * - minor field + only allFields: hide
     */
    public boolean showsField(Field field) {
        if (field == null) {
            return false;
        }

        String name = field.getName();

        if (fields.containsKey(name)) {
            return true;
        }

        boolean minor = ViewableAdapter.isMinorField(field);

        return minor ? allMinorFields : allFields;
    }

    /** Show-decision by field NAME — for a dynamic (map-held) field with no
     *  declared {@link Field} (no minor-field concept). */
    public boolean showsFieldByName(String name) {
        return name != null && (fields.containsKey(name) || allFields);
    }

    /**
     * Returns a configuration suitable for rendering a child field.
     * Child parameters override the parent, but display flags cascade down.
     */
    public ViewablePanelConfig mergedForChild(ViewablePanelConfig child) {
        if (child == null) {
            ViewablePanelConfig merged = new ViewablePanelConfig();

            merged.cls = this.cls;
            merged.allFields = this.allFields;
            merged.allMinorFields = this.allMinorFields;
            merged.addListener = this.addListener;
            merged.thumb = this.thumb;
            merged.answerType = this.answerType;

            for (Map.Entry<String, ViewablePanelConfig> e : this.fields.entrySet()) {
                merged.fields.put(e.getKey(), e.getValue().copy());
            }

            return merged;
        }

        ViewablePanelConfig merged = child.copy();

        if (merged.getCls() == null) {
            merged.cls = this.cls;
        }

        merged.setAddListener(merged.isAddListener() || this.isAddListener());
        merged.setThumb(merged.isThumb() || this.isThumb());
        merged.setBlurImages(merged.isBlurImages() || this.isBlurImages());

        if (merged.getAnswerType() == AnswerType.AUTO) {
            merged.setAnswerType(this.getAnswerType());
        }

        return merged;
    }

    public boolean hasField(String name) {
        return getFieldConfig(name) != null;
    }

    public ViewablePanelConfig getFieldConfig(String name) {
        return name == null ? null : fields.get(name);
    }

    public void addField(String name, ViewablePanelConfig config) {
        fields.put(name, config);
    }

    public Map<String, ViewablePanelConfig> getFields() {
        return fields;
    }

    public boolean isAllFields() {
        return allFields;
    }

    public void setAllFields(boolean v) {
        allFields = v;
    }

    public boolean isAllMinorFields() {
        return allMinorFields;
    }

    public ViewablePanelConfig setAllMinorFields(boolean allMinorFields) {
        this.allMinorFields = allMinorFields;
        return this;
    }

    public Class<? extends Viewable> getCls() {
        return cls;
    }

    public ViewablePanelConfig setCls(Class<? extends Viewable> c) {
        cls = c;
        return this;
    }

    public boolean isAddListener() {
        return addListener;
    }

    public ViewablePanelConfig setAddListener(boolean v) {
        addListener = v;
        return this;
    }

    public boolean isThumb() {
        return thumb;
    }

    public ViewablePanelConfig setThumb(boolean v) {
        thumb = v;
        return this;
    }

    public boolean isBlurImages() {
        return blurImages;
    }

    public ViewablePanelConfig setBlurImages(boolean v) {
        blurImages = v;
        return this;
    }

    public AnswerType getAnswerType() {
        return answerType;
    }

    public void setAnswerType(AnswerType t) {
        answerType = t;
    }

    public List<Field> visibleFieldsFor(Class<?> cls) {
        if (cls == null) {
            return List.of();
        }

        return visibleFieldsCache.computeIfAbsent(cls, c -> {
            List<Field> result = new ArrayList<>();

            for (Field field : ViewableAdapter.getAllFields(c)) {
                if ("name".equals(field.getName())) {
                    continue;
                }

                if (showsField(field)) {
                    result.add(field);
                }
            }

            return Collections.unmodifiableList(result);
        });
    }

    @Override
    public String toString() {
        return "Config{" + "cls=" + (cls == null ? "?" : cls.getSimpleName()) +
                ", allFields=" + allFields + ", allMinorFields=" + allMinorFields +
                ", addListener=" + addListener + ", thumb=" + thumb + ", type=" +
                answerType + ", fields=" + fields.keySet() + '}';
    }

    public enum AnswerType {
        AUTO, TEXT, NUMBER, BOOLEAN, MULTIPLE_CHOICE, IMAGE
    }
}