package quiz.transform.ui;

import objectview.field.FieldKind;
import objectview.field.FieldPath;
import objectview.field.FieldRef;
import objectview.field.FieldSchema;
import objectview.viewconfig.FieldTypeSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projections and queries over the canonical {@link FieldSchema}.
 *
 * <p>{@link DomainField} (dotted operation paths) and {@link FieldTypeSource}
 * (the existing config editor API) are projections of the schema, not independent
 * sources of truth. Keeping those projections here prevents each UI/persistence
 * surface from re-inferring collection element kinds, reference targets and
 * structural roles differently.
 */
public final class DomainSchemas {

    private static final int MAX_PATH_DEPTH = 6;

    // Warn AT MOST once per (topType, nested type) that a deep reference chain was truncated,
    // so the MAX_PATH_DEPTH cap is never silent but also never spams.
    private static final Set<String> WARNED_DEPTH =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private DomainSchemas() {}

    /** Dotted operation fields projected recursively from canonical schemas. */
    public static List<DomainField> fields(DomainModel domain, String type) {
        List<DomainField> out = new ArrayList<>();
        collectFields(domain, type, type, FieldPath.ROOT,
                new LinkedHashSet<>(), 0, out);
        return out;
    }

    private static void collectFields(
            DomainModel domain, String topType, String currentType,
            FieldPath prefix, Set<String> chain, int depth,
            List<DomainField> out) {
        if (currentType == null || currentType.isBlank()) {
            return;
        }
        if (depth > MAX_PATH_DEPTH) {
            if (WARNED_DEPTH.add(topType + " > " + currentType)) {
                System.err.println("DomainSchemas: '" + topType + "' field enumeration truncated"
                        + " at depth " + MAX_PATH_DEPTH + " (nested reference via '" + currentType
                        + "') — deeper fields are not offered as operation paths.");
            }
            return;
        }
        if (!chain.add(currentType)) {
            return;
        }
        FieldSchema schema = domain.fieldSchema(currentType);
        // Identity/display are the read-only Viewable contract, not operation fields —
        // they must not enter this (writable) operation pool. Consumers that need the
        // display use getDisplayName() directly, not a field path.
        if (schema == null) {
            chain.remove(currentType);
            return;
        }
        for (FieldRef field : schema.fields()) {
            if (field.structural()) {
                // structural = reify/back-ref plumbing, not a domain data field.
                continue;
            }
            FieldPath path = prefix.append(field.name());
            out.add(new DomainField(topType, path, field.reference(),
                    field.collection(), field.kind()));
            if (field.reference() && field.targetType() != null
                    && !field.targetType().isBlank()) {
                collectFields(domain, topType, field.targetType(), path,
                        new LinkedHashSet<>(chain), depth + 1, out);
            }
        }
        chain.remove(currentType);
    }

    public static Set<String> structuralFields(FieldSchema schema) {
        if (schema == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (FieldRef field : schema.fields()) {
            if (field.structural()) {
                out.add(field.name());
            }
        }
        return Set.copyOf(out);
    }

    /** Existing config-editor view projected from the canonical schemas. */
    public static FieldTypeSource fieldTypes(
            DomainModel domain, String type) {
        return new SchemaFieldTypeSource(domain, type, new LinkedHashSet<>());
    }

    /** The subtype "class branches" for {@code baseType}: one branch per subtype that
     *  introduces its OWN fields, so every field picker (coverage, filter, view) shows a
     *  subclass's own fields under its heading. The SINGLE subclass-aware enumeration all
     *  pickers share — instead of each one re-deriving it (or omitting it, which hid e.g.
     *  {@code USState.admissionDate} from the filter picker). Only fields introduced by the
     *  subtype occur under its heading; base fields stay in the base branch. */
    public static List<objectview.viewconfig.ViewConfigEditor.ClassBranch> classBranches(
            DomainModel domain, String baseType) {
        if (domain == null || baseType == null) {
            return List.of();
        }
        List<objectview.viewconfig.ViewConfigEditor.ClassBranch> branches = new ArrayList<>();
        for (String subtype : domain.subtypesOf(baseType)) {
            Set<String> additional = domain.additionalFields(subtype);
            if (additional.isEmpty()) {
                continue;
            }
            objectview.viewconfig.ViewConfig config = new objectview.viewconfig.ViewConfig();
            config.setAllFields(false);
            for (String field : additional) {
                config.addField(field, objectview.viewconfig.ViewConfig.leaf());
            }
            objectview.Viewable sample = domain.representativeSample(subtype);
            @SuppressWarnings("unchecked")
            Class<? extends objectview.Viewable> cls = sample == null
                    ? objectview.Viewable.class
                    : (Class<? extends objectview.Viewable>) sample.getClass();
            branches.add(new objectview.viewconfig.ViewConfigEditor.ClassBranch(
                    subtype, domain.baseType(subtype), cls,
                    onlyFields(domain.fieldTypes(subtype), additional), config));
        }
        return List.copyOf(branches);
    }

    private static FieldTypeSource onlyFields(FieldTypeSource source, Set<String> fields) {
        Set<String> included = fields == null ? Set.of()
                : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(fields));
        return new FieldTypeSource() {
            @Override public FieldTypeInfo field(String name) {
                return included.contains(name) && source != null ? source.field(name) : null;
            }
            @Override public List<String> fieldNames() {
                return List.copyOf(included);
            }
        };
    }

    /** Schema for a flat derived class described by its operation fields. */
    public static FieldSchema flatSchema(List<DomainField> fields) {
        List<FieldRef> refs = new ArrayList<>();
        if (fields != null) {
            for (DomainField field : fields) {
                if (field == null || field.field() == null
                        || field.field().contains(".")) {
                    continue;
                }
                FieldKind valueKind;
                if (field.reference()) {
                    valueKind = FieldKind.REFERENCE;
                } else if (field.collection()
                        && field.kind() == FieldKind.COLLECTION) {
                    valueKind = FieldKind.UNKNOWN;
                } else {
                    valueKind = field.kind();
                }
                FieldKind kind = field.collection() ? FieldKind.COLLECTION
                        : field.reference() ? FieldKind.REFERENCE : field.kind();
                refs.add(FieldRef.described(field.field(), kind,
                        valueKind, typeLabel(field), field.reference(),
                        field.collection(), null, false, false,
                        false, false, "", false));
            }
        }
        List<FieldRef> immutable = List.copyOf(refs);
        return () -> immutable;
    }

    /** Resolve one dotted path to its canonical leaf description. */
    public static FieldRef resolve(
            DomainModel domain, String type, String dottedPath) {
        return resolve(domain, type, FieldPath.parse(dottedPath));
    }

    public static FieldRef resolve(
            DomainModel domain, String type, FieldPath path) {
        if (domain == null || type == null || path == null || path.isRoot()) {
            return null;
        }
        String currentType = type;
        List<String> parts = path.segments();
        FieldRef current = null;
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            FieldSchema schema = domain.fieldSchema(currentType);
            current = schema == null ? null : schema.field(part);
            if (current != null) {
                if (i < parts.size() - 1) {
                    currentType = current.targetType();
                    if (currentType == null || currentType.isBlank()) return null;
                }
                continue;
            }
            FieldRef contract = objectview.field.ViewableContractFieldSet.fieldRefs().stream()
                    .filter(field -> field.name().equals(part))
                    .findFirst().orElse(null);
            if (contract != null) {
                return i == parts.size() - 1 ? contract : null;
            }
            return null;
        }
        return current;
    }

    /** Copy a field description under a projected/derived field name. */
    public static FieldRef renamed(FieldRef field, String name) {
        if (field == null) {
            return null;
        }
        return FieldRef.withName(field, name);
    }

    private static String typeLabel(DomainField field) {
        String scalar = switch (field.kind()) {
            case BOOLEAN -> "Boolean";
            case ORDERED -> "Ordered";
            case TEXT -> "String";
            case MEDIA -> "MediaValue";
            case REFERENCE -> "Viewable";
            default -> "Object";
        };
        return field.collection() ? "Collection<" + scalar + ">" : scalar;
    }

    private static boolean isContainerLabel(String label) {
        if (label == null) {
            return false;
        }
        String trimmed = label.trim();
        return trimmed.equals("Collection") || trimmed.startsWith("Collection<")
                || trimmed.equals("List") || trimmed.startsWith("List<")
                || trimmed.equals("Set") || trimmed.startsWith("Set<")
                || trimmed.equals("Map") || trimmed.startsWith("Map<")
                || trimmed.endsWith("[]");
    }

    private static String elementTypeLabel(String label) {
        if (label == null) {
            return "";
        }
        String trimmed = label.trim();
        int open = trimmed.indexOf('<');
        int close = trimmed.lastIndexOf('>');
        if (open >= 0 && close > open) {
            String element = trimmed.substring(open + 1, close).trim();
            int comma = element.lastIndexOf(',');
            return comma < 0 ? element
                    : element.substring(comma + 1).trim();
        }
        return trimmed.endsWith("[]")
                ? trimmed.substring(0, trimmed.length() - 2) : trimmed;
    }

    private record SchemaFieldTypeSource(
            DomainModel domain, String type,
            Set<String> ancestorTypes) implements FieldTypeSource {

        @Override public FieldTypeInfo field(String name) {
            FieldSchema schema = domain.fieldSchema(type);
            FieldRef field = schema == null ? null : schema.field(name);
            if (field != null) {
                return schemaInfo(field);
            }
            FieldRef contract = objectview.field.ViewableContractFieldSet.fieldRefs().stream()
                    .filter(candidate -> candidate.name().equals(name))
                    .findFirst().orElse(null);
            if (contract != null) {
                return new FieldTypeInfo(contract.typeLabel(), false, false,
                        null, null, contract.label(), contract.role(),
                        contract.kind(), contract.valueKind());
            }
            return null;
        }

        private FieldTypeInfo schemaInfo(FieldRef field) {
            String target = field.targetType();
            FieldTypeSource nested = null;
            if (!field.structural() && target != null
                    && !target.isBlank() && !ancestorTypes.contains(target)) {
                FieldSchema targetSchema = domain.fieldSchema(target);
                if (targetSchema != null && targetSchema.fields().stream()
                        .anyMatch(f -> !f.structural())) {
                    Set<String> next = new LinkedHashSet<>(ancestorTypes);
                    next.add(type);
                    nested = new SchemaFieldTypeSource(domain, target, next);
                }
            }
            return new FieldTypeInfo(field.typeLabel(), field.structural(),
                    field.minor(), nested == null ? null : target, nested,
                    field.label(), field.role(), field.kind(), field.valueKind());
        }

        @Override public List<String> fieldNames() {
            FieldSchema schema = domain.fieldSchema(type);
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            // Same rule as the rendering path (ViewableContractFieldSet.overlay): the
            // synthetic display field is offered ONLY when no real field is bound to
            // DISPLAY, so a @DisplayField type doesn't show both "Display label" and its
            // real name field in config.
            boolean hasDisplayField = schema != null && schema.fields().stream()
                    .anyMatch(f -> f.role() == objectview.field.FieldRole.DISPLAY);
            if (!hasDisplayField) {
                objectview.field.ViewableContractFieldSet.fieldRefs().stream()
                        .map(FieldRef::name).forEach(names::add);
            }
            if (schema != null) schema.fields().stream().map(FieldRef::name).forEach(names::add);
            return List.copyOf(names);
        }
    }
}
