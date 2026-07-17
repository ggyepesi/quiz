package quiz;

import objectview.ViewableAdapter;
import objectview.viewconfig.ViewablePanelConfig;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public abstract class QuizableAdapter
        extends ViewableAdapter
        implements Quizable {    @Override
    public abstract String getIdentifier();

    @Override
    public abstract String getDisplayName();

    /**
     * Creates a blank instance of this class for projection / cartesian-product mechanics.
     * The default implementation uses reflection and requires an accessible no-arg constructor.
     * Override when no-arg construction is impossible or requires special initialization.
     */
    public QuizableAdapter createNew() {
        try {
            java.lang.reflect.Constructor<? extends QuizableAdapter> ctor =
                    getClass().getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new UnsupportedOperationException(
                    "createNew() requires a no-arg constructor on " + getClass().getSimpleName(), e);
        }
    }


    public HashMap<List<Object>, Quizable> generateUniqueCombinations(List<String> fieldNames) {
        ViewablePanelConfig cfg = ViewablePanelConfig.of(getClass());
        if (fieldNames != null) {
            for (String name : fieldNames) {
                cfg.addField(name, ViewablePanelConfig.leaf());
            }
        }
        return generateUniqueCombinations(cfg);
    }

    public HashMap<List<Object>, Quizable> generateUniqueCombinations(ViewablePanelConfig config) {
        if (config == null) {
            return new HashMap<>();
        }

        LinkedHashSet<String> fieldNames = new LinkedHashSet<>(config.getFields().keySet());
        HashMap<List<Object>, Quizable> combinations = new HashMap<>();

        if (fieldNames.isEmpty()) {
            combinations.put(new ArrayList<>(), createNew());
            return combinations;
        }

        List<FieldChoiceBundle> perFieldChoices = new ArrayList<>();

        for (String fieldName : fieldNames) {
            Field field = getField(getClass(), fieldName);
            if (field == null) {
                continue;
            }

            Object fieldValue;
            try {
                fieldValue = field.get(this);
            } catch (IllegalAccessException e) {
                continue;
            }

            if (fieldValue == null) {
                continue;
            }

            ViewablePanelConfig childConfig = config.getFieldConfig(fieldName);
            List<FieldAlternative> alternatives = generateFieldAlternatives(field, fieldValue, childConfig);

            if (!alternatives.isEmpty()) {
                perFieldChoices.add(new FieldChoiceBundle(fieldName, field, alternatives));
            }
        }

        if (perFieldChoices.isEmpty()) {
            combinations.put(new ArrayList<>(), createNew());
            return combinations;
        }

        buildCombinations(perFieldChoices, 0, new ArrayList<>(), new ArrayList<>(), combinations);
        return combinations;
    }

    private void buildCombinations(List<FieldChoiceBundle> bundles,
                                   int index,
                                   List<Object> currentKey,
                                   List<SelectedFieldValue> selectedValues,
                                   Map<List<Object>, Quizable> out) {

        if (index == bundles.size()) {
            Quizable projected = projectSelectedValues(selectedValues);
            out.put(new ArrayList<>(currentKey), projected);
            return;
        }

        FieldChoiceBundle bundle = bundles.get(index);
        for (FieldAlternative alt : bundle.alternatives) {
            int oldKeySize = currentKey.size();
            int oldSelectedSize = selectedValues.size();

            currentKey.addAll(alt.keyTokens);
            selectedValues.add(new SelectedFieldValue(bundle.fieldName, bundle.field, alt.projectedValue));

            buildCombinations(bundles, index + 1, currentKey, selectedValues, out);

            while (currentKey.size() > oldKeySize) {
                currentKey.remove(currentKey.size() - 1);
            }
            while (selectedValues.size() > oldSelectedSize) {
                selectedValues.remove(selectedValues.size() - 1);
            }
        }
    }

    private List<FieldAlternative> generateFieldAlternatives(Field field,
                                                             Object fieldValue,
                                                             ViewablePanelConfig childConfig) {

        Class<?> fieldType = field.getType();
        List<FieldAlternative> alternatives = new ArrayList<>();

        if (Collection.class.isAssignableFrom(fieldType)) {
            for (Object element : (Collection<?>) fieldValue) {
                alternatives.addAll(generateValueAlternatives(element, childConfig, false, null));
            }
            return alternatives;
        }

        if (Map.class.isAssignableFrom(fieldType)) {
            Map<?, ?> map = (Map<?, ?>) fieldValue;
            for (Entry<?, ?> e : map.entrySet()) {
                alternatives.addAll(generateValueAlternatives(e.getValue(), childConfig, true, e.getKey()));
            }
            return alternatives;
        }

        alternatives.addAll(generateValueAlternatives(fieldValue, childConfig, false, null));
        return alternatives;
    }

    private List<FieldAlternative> generateValueAlternatives(Object value,
                                                             ViewablePanelConfig childConfig,
                                                             boolean isMapValue,
                                                             Object mapKey) {
        List<FieldAlternative> alternatives = new ArrayList<>();

        if (value == null) {
            return alternatives;
        }

        if (value instanceof QuizableAdapter qa
                && childConfig != null
                && !childConfig.getFields().isEmpty()) {
            HashMap<List<Object>, Quizable> nested = qa.generateUniqueCombinations(childConfig);
            for (Entry<List<Object>, Quizable> e : nested.entrySet()) {
                List<Object> tokens = new ArrayList<>();
                if (isMapValue) {
                    tokens.add(mapKey);
                }
                tokens.addAll(e.getKey());

                Object projectedValue = isMapValue
                        ? new MEntry(mapKey, e.getValue())
                        : e.getValue();

                alternatives.add(new FieldAlternative(tokens, projectedValue));
            }
            return alternatives;
        }

        if (isMapValue) {
            alternatives.add(new FieldAlternative(
                    new ArrayList<>(List.of(mapKey, summarizeSimple(value))),
                    new MEntry(mapKey, value)));
        } else {
            alternatives.add(new FieldAlternative(
                    new ArrayList<>(List.of(summarizeSimple(value))),
                    value));
        }

        return alternatives;
    }

    private Quizable projectSelectedValues(List<SelectedFieldValue> selectedValues) {
        QuizableAdapter other = createNew();

        for (SelectedFieldValue selected : selectedValues) {
            try {
                Field otherField = getField(other.getClass(), selected.fieldName);
                if (otherField == null) {
                    continue;
                }

                Object otherFieldValue = otherField.get(other);
                Class<?> fieldType = selected.field.getType();
                Object value = selected.value;

                if (Collection.class.isAssignableFrom(fieldType)) {
                    @SuppressWarnings("unchecked")
                    Collection<Object> collection = (Collection<Object>) otherFieldValue;
                    collection.add(value);
                } else if (Map.class.isAssignableFrom(fieldType)) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> map = (Map<Object, Object>) otherFieldValue;
                    MEntry me = (MEntry) value;
                    map.put(me.key, me.value);
                } else {
                    otherField.set(other, value);
                }
            } catch (Exception e) {
                System.out.println("Error projecting " + selected.fieldName + " on " + this);
                e.printStackTrace();
            }
        }

        return other;
    }

    public Quizable project(ViewablePanelConfig config, List<Object> flatValues) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        Index index = new Index();
        QuizableAdapter projected = projectRecursive(config, flatValues, index);

        if (index.pos != flatValues.size()) {
            throw new IllegalArgumentException(
                    "Unused values remain: consumed " + index.pos + " of " + flatValues.size());
        }

        return projected;
    }

    private QuizableAdapter projectRecursive(ViewablePanelConfig config,
                                             List<Object> flatValues,
                                             Index index) {
        QuizableAdapter other = createNew();

        for (String fieldName : config.getFields().keySet()) {
            Field field = getField(getClass(), fieldName);
            if (field == null) {
                continue;
            }

            ViewablePanelConfig childConfig = config.getFieldConfig(fieldName);

            try {
                Field otherField = getField(other.getClass(), fieldName);
                if (otherField == null) {
                    continue;
                }

                Object currentTargetValue = otherField.get(other);
                ProjectionResult result = projectFieldValue(
                        field,
                        childConfig,
                        flatValues,
                        index,
                        currentTargetValue);

                if (result.assign) {
                    otherField.set(other, result.value);
                }
            } catch (Exception e) {
                System.out.println("Error projecting field " + fieldName + " on " + this);
                e.printStackTrace();
            }
        }

        return other;
    }

    @SuppressWarnings("unchecked")
    private ProjectionResult projectFieldValue(Field field,
                                               ViewablePanelConfig childConfig,
                                               List<Object> flatValues,
                                               Index index,
                                               Object currentTargetValue) throws Exception {

        Class<?> fieldType = field.getType();

        if (Collection.class.isAssignableFrom(fieldType)) {
            if (currentTargetValue == null) {
                return ProjectionResult.keepExisting();
            }

            Object next = nextValue(flatValues, index);
            ((Collection<Object>) currentTargetValue).add(next);
            return ProjectionResult.keepExisting();
        }

        if (Map.class.isAssignableFrom(fieldType)) {
            if (currentTargetValue == null) {
                return ProjectionResult.keepExisting();
            }

            Object key = nextValue(flatValues, index);
            Object value = nextValue(flatValues, index);
            ((Map<Object, Object>) currentTargetValue).put(key, value);
            return ProjectionResult.keepExisting();
        }

        if (Quizable.class.isAssignableFrom(fieldType)
                && childConfig != null
                && !childConfig.getFields().isEmpty()) {

            if (!QuizableAdapter.class.isAssignableFrom(fieldType)) {
                return ProjectionResult.assign(nextValue(flatValues, index));
            }

            QuizableAdapter nested;
            if (currentTargetValue instanceof QuizableAdapter qa) {
                nested = qa;
            } else {
                Object originalFieldValue = field.get(this);
                if (originalFieldValue instanceof QuizableAdapter qa) {
                    nested = qa.createNew();
                } else {
                    return ProjectionResult.assign(nextValue(flatValues, index));
                }
            }

            return ProjectionResult.assign(nested.projectRecursive(childConfig, flatValues, index));
        }

        return ProjectionResult.assign(nextValue(flatValues, index));
    }

    private Object nextValue(List<Object> flatValues, Index index) {
        if (index.pos >= flatValues.size()) {
            throw new IllegalArgumentException("Not enough values for projection");
        }
        return flatValues.get(index.pos++);
    }

    private static class Index {
        int pos = 0;
    }

    private static class ProjectionResult {
        final Object value;
        final boolean assign;

        ProjectionResult(Object value, boolean assign) {
            this.value = value;
            this.assign = assign;
        }

        static ProjectionResult assign(Object value) {
            return new ProjectionResult(value, true);
        }

        static ProjectionResult keepExisting() {
            return new ProjectionResult(null, false);
        }
    }

    private Object summarizeSimple(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Quizable q) {
            return q.getIdentifier();
        }
        return value;
    }

    private class FieldChoiceBundle {
        String fieldName;
        Field field;
        List<FieldAlternative> alternatives;

        FieldChoiceBundle(String fieldName, Field field, List<FieldAlternative> alternatives) {
            this.fieldName = fieldName;
            this.field = field;
            this.alternatives = alternatives;
        }
    }

    private class FieldAlternative {
        List<Object> keyTokens;
        Object projectedValue;

        FieldAlternative(List<Object> keyTokens, Object projectedValue) {
            this.keyTokens = keyTokens;
            this.projectedValue = projectedValue;
        }
    }

    private class SelectedFieldValue {
        String fieldName;
        Field field;
        Object value;

        SelectedFieldValue(String fieldName, Field field, Object value) {
            this.fieldName = fieldName;
            this.field = field;
            this.value = value;
        }
    }

    private class MEntry {
        Object key;
        Object value;

        MEntry(Object key, Object value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public String toString() {
            return key + " -> " + value;
        }
    }

    @SuppressWarnings("unchecked")
    public Quizable project(List<String> fieldNames, List<Object> fieldValues) {
        if (fieldNames.size() != fieldValues.size()) {
            throw new IllegalArgumentException();
        }

        QuizableAdapter other = createNew();
        Class<?> cls = getClass();

        for (int i = 0; i < fieldNames.size(); ++i) {
            try {
                Field field = getField(cls, fieldNames.get(i));
                Object value = fieldValues.get(i);
                Class<?> fieldType = field.getType();
                Field otherField = getField(other.getClass(), field.getName());
                Object otherFieldValue = otherField.get(other);

                if (otherFieldValue == null) {
                    otherField.set(other, value);
                } else if (Quizable.class.isAssignableFrom(fieldType)) {
                    otherField.set(other, ((Quizable) otherFieldValue).project(fieldNames, fieldValues));
                } else if (Collection.class.isAssignableFrom(fieldType)) {
                    ((Collection<Object>) otherFieldValue).add(value);
                } else if (Map.class.isAssignableFrom(fieldType)) {
                    MEntry me = (MEntry) value;
                    ((Map<Object, Object>) otherFieldValue).put(me.key, me.value);
                } else {
                    otherField.set(other, value);
                }
            } catch (Exception e) {
                System.out.println("Error at " + fieldNames.get(i) + ", " + this);
            }
        }
        return other;
    }
}