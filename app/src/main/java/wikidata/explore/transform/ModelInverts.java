package wikidata.explore.transform;

import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledField;
import wikidata.explore.compiled.CompiledProjectModel;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldProductionKind;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Turns a model's {@code production = INVERT} fields into {@link InvertConstruct}s,
 * so an inverse relationship is configured ON THE FIELD (not in a separate
 * Transform file) and applied automatically after generation. A field {@code
 * C.back} with production INVERT, referencing class {@code S} via property {@code
 * P}, is the reverse of the forward field on {@code S} that references {@code C}
 * via the same {@code P} (e.g. {@code Category.nominees} ↔
 * {@code Oscarnominations.categories}, both P1411). The invert then walks the
 * already-generated forward references and fills {@code C.back} in memory.
 */
public final class ModelInverts {

    private ModelInverts() {}

    /** Derive every model invert and apply it to the pool in place (no query). */
    public static void apply(GeneratedProjectModel project,
                             Collection<WikidataDynamicObject> pool,
                             GenerationLog log) {
        apply(derive(project), pool, log);
    }

    /** Compiled-model overload — same invert application, compiled derivation. */
    public static void apply(CompiledProjectModel project,
                             Collection<WikidataDynamicObject> pool,
                             GenerationLog log) {
        apply(derive(project), pool, log);
    }

    private static void apply(List<InvertConstruct> inverts,
                              Collection<WikidataDynamicObject> pool,
                              GenerationLog log) {
        if (inverts.isEmpty()) {
            return;
        }
        TransformEngine engine = new TransformEngine();
        for (InvertConstruct c : inverts) {
            engine.applyInvert(pool, c);
            if (log != null) {
                log.message("Invert " + c.sourceType() + "." + c.refField()
                        + " -> " + c.targetType() + "." + c.backRefField() + "\n");
            }
        }
    }

    public static List<InvertConstruct> derive(GeneratedProjectModel project) {
        List<InvertConstruct> out = new ArrayList<>();
        if (project == null) {
            return out;
        }
        for (GeneratedClassModel target : project.classes()) {
            for (GeneratedFieldModel back : target.fields()) {
                if (back == null
                        || back.mapping().productionKind() != FieldProductionKind.INVERT) {
                    continue;
                }
                String sourceType = back.entityClassName();        // S (referenced class)
                if (sourceType == null || sourceType.isBlank()) {
                    continue;
                }
                GeneratedClassModel src = project.findClass(sourceType);
                if (src == null) {
                    continue;
                }
                String refField = forwardField(src, target.className(),
                        clean(back.mapping().propertyPid()),
                        clean(back.mapping().inverseField()));
                if (refField == null) {
                    continue;
                }
                // Use the RESOLVED class's actual name so the construct matches the
                // pool's stamped typeName (the Of-class label may differ in case).
                out.add(new InvertConstruct(
                        src.className(), refField, target.className(), back.name()));
            }
        }
        return out;
    }

    /** Compiled-model overload of {@link #derive(GeneratedProjectModel)}. The
     *  compiled entityClassName is already resolved to the actual class name, so
     *  the source lookup and forward-field match are on resolved names. */
    public static List<InvertConstruct> derive(CompiledProjectModel project) {
        List<InvertConstruct> out = new ArrayList<>();
        if (project == null) {
            return out;
        }
        for (CompiledClass target : project.classes()) {
            for (CompiledField back : target.ownFields()) {
                if (back.source().productionKind() != FieldProductionKind.INVERT) {
                    continue;
                }
                String sourceType = back.entityClassName();   // resolved class S
                if (sourceType.isBlank()) {
                    continue;
                }
                CompiledClass src = project.findClass(sourceType).orElse(null);
                if (src == null) {
                    continue;
                }
                String refField = forwardField(src, target.className(),
                        clean(back.source().propertyPid()),
                        clean(back.source().inverseField()));
                if (refField == null) {
                    continue;
                }
                out.add(new InvertConstruct(
                        src.className(), refField, target.className(), back.name()));
            }
        }
        return out;
    }

    private static String forwardField(CompiledClass src,
                                       String targetClass, String pid,
                                       String explicitField) {
        List<String> byProperty = new ArrayList<>();
        List<String> byClass = new ArrayList<>();
        for (CompiledField f : src.ownFields()) {
            if (!targetClass.equals(f.entityClassName())) {
                continue;
            }
            byClass.add(f.name());
            if (!pid.isBlank() && pid.equals(clean(f.source().propertyPid()))) {
                byProperty.add(f.name());
            }
        }
        return wikidata.explore.model.InverseFieldResolution.resolve(
                explicitField, byClass, byProperty);
    }

    /** The forward field on {@code src} that references {@code targetClass} via the
     *  same property — the field whose references we invert. */
    private static String forwardField(GeneratedClassModel src,
                                       String targetClass, String pid,
                                       String explicitField) {
        List<String> byProperty = new ArrayList<>();
        List<String> byClass = new ArrayList<>();
        for (GeneratedFieldModel f : src.fields()) {
            if (f == null || !targetClass.equals(f.entityClassName())) {
                continue;
            }
            byClass.add(f.name());
            if (!pid.isBlank() && pid.equals(clean(f.mapping().propertyPid()))) {
                byProperty.add(f.name());
            }
        }
        return wikidata.explore.model.InverseFieldResolution.resolve(
                explicitField, byClass, byProperty);
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }
}
