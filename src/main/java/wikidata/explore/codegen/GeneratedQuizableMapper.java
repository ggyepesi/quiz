package wikidata.explore.codegen;

import wikidata.explore.extract.WikidataMediaValue;
import wikidata.explore.extract.WikidataDynamicObject;
import quiz.Quizable;
import quiz.ui.ImagePane;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedFieldModel;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class GeneratedQuizableMapper {
    private final GeneratedQuizableRuntime runtime;
    private final Map<WikidataDynamicObject, Object> generatedByDynamic =
            new IdentityHashMap<>();

    public GeneratedQuizableMapper(GeneratedQuizableRuntime runtime) {
        this.runtime = runtime;
    }

    public List<Quizable> mapRoots(List<WikidataDynamicObject> roots) throws Exception {
        List<Quizable> out = new ArrayList<>();
        if (roots == null) return out;
        for (WikidataDynamicObject root : roots) {
            Object mapped = mapObject(root);
            if (mapped instanceof Quizable q) out.add(q);
        }
        return out;
    }

    private Object mapObject(WikidataDynamicObject source) throws Exception {
        if (source == null) return null;
        Object existing = generatedByDynamic.get(source);
        if (existing != null) return existing;

        Object target = runtime.generatedClass().getDeclaredConstructor().newInstance();
        generatedByDynamic.put(source, target);

        setIfExists(target, "qid", source.qid());
        setIfExists(target, "wikidataUrl", source.wikidataUrl());
        setIfExists(target, "name", source.getDisplayName());

        for (GeneratedFieldModel fieldModel : runtime.model().fields()) {
            if (fieldModel == null || fieldModel.isNameField()) continue;

            String targetFieldName =
                    GeneratedQuizableSourceGenerator.sanitizeFieldName(fieldModel.name());
            Field javaField = findField(runtime.generatedClass(), targetFieldName);
            if (javaField == null) continue;

            Object raw = source.get(fieldModel.name());
            if (raw == null) continue;

            Object mapped = mapFieldValue(fieldModel, raw);
            if (mapped != null) {
                javaField.setAccessible(true);
                javaField.set(target, mapped);
            }
        }

        return target;
    }

    private Object mapFieldValue(GeneratedFieldModel fieldModel, Object raw) throws Exception {
        if (fieldModel.cardinality() == FieldCardinality.COLLECTION) {
            List<Object> list = new ArrayList<>();
            if (raw instanceof Iterable<?> iterable) {
                for (Object item : iterable) {
                    Object mapped = mapSingleValue(fieldModel, item);
                    if (mapped != null) list.add(mapped);
                }
            } else {
                Object mapped = mapSingleValue(fieldModel, raw);
                if (mapped != null) list.add(mapped);
            }
            return list;
        }

        // Single-valued field, but Wikidata can return several values for a
        // property (e.g. a constellation "named after" multiple figures), which
        // the extractor merges into a List. The generated field holds one
        // value, so map the first mappable element and drop the rest rather
        // than crashing on a List -> scalar assignment.
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                Object mapped = mapSingleValue(fieldModel, item);
                if (mapped != null) {
                    return mapped;
                }
            }
            return null;
        }
        return mapSingleValue(fieldModel, raw);
    }

    private Object mapSingleValue(GeneratedFieldModel fieldModel, Object raw) throws Exception {
        if (raw == null) return null;
        if (effectiveType(fieldModel) == FieldType.IMAGE) {
            if (raw instanceof ImagePane) {
                return raw;
            }

            if (raw instanceof WikidataMediaValue media) {
                return toImagePane(media);
            }

            return null;
        }

        if (fieldModel.type() == FieldType.ENTITY || raw instanceof WikidataDynamicObject) {
            return raw instanceof WikidataDynamicObject dyn ? mapObject(dyn) : raw;
        }
        if (fieldModel.type() == FieldType.NUMBER) {
            if (raw instanceof Number n) return n.doubleValue();
            try { return Double.parseDouble(String.valueOf(raw)); }
            catch (Exception ignored) { return null; }
        }

        return raw;
    }

    private FieldType effectiveType(GeneratedFieldModel field) {
        String pid =
                field.mapping() == null ? "" : field.mapping().propertyPid();

        if ("P18".equals(pid) || "P242".equals(pid)) {
            return FieldType.IMAGE;
        }

        return field.type();
    }

    private ImagePane toImagePane(WikidataMediaValue media) {
        try {
            System.out.println("Mapper.toImagePane lazy " + media.url());
            return new ImagePane(
                    media.label(),
                    media.url(),
                    null,
                    false,
                    media.svg(),
                    false);   // loadThumbnailImmediately = false

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setIfExists(Object target, String fieldName, Object value) throws Exception {
        Field f = findField(target.getClass(), fieldName);
        if (f == null) return;
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Field findField(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null) {
            try { return c.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
        }
        return null;
    }
}
