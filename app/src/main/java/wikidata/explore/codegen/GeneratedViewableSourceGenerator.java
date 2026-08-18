package wikidata.explore.codegen;

import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GeneratedViewableSourceGenerator {
    public static final String GENERATED_PACKAGE = "wikidata.generated";

    private final String packageName;

    public GeneratedViewableSourceGenerator(String packageName) {
        this.packageName =
                packageName == null || packageName.isBlank()
                        ? GENERATED_PACKAGE
                        : packageName;
    }

    public String qualifiedClassName(GeneratedClassModel model) {
        return packageName + "." + sanitizeClassName(model.className());
    }

    public String sourceFor(GeneratedClassModel model) {
        return sourceFor(model, null);
    }

    /**
     * Source for a class. With a {@code project}, its <b>effective</b> fields
     * (inherited from the {@code extends} base chain + its own) are emitted —
     * the generated runtime compiles each class independently (no cross-class
     * Java inheritance), so inherited fields must be flattened in as real
     * declared fields or the mapper would drop them. Inherited fields are
     * tagged with a comment so the source still shows where they came from.
     */
    public String sourceFor(GeneratedClassModel model, GeneratedProjectModel project) {
        String className = sanitizeClassName(model.className());

        List<GeneratedFieldModel> fields =
                project == null ? model.fields() : model.effectiveFields(project);

        // Own (non-inherited) field names, to tag inherited ones in the source.
        Set<String> ownNames = new HashSet<>();
        for (GeneratedFieldModel f : model.fields()) {
            if (f != null && f.name() != null) {
                ownNames.add(f.name());
            }
        }

        StringBuilder sb = new StringBuilder();

        sb.append("package ").append(packageName).append(";\n\n");

        boolean needsReferenceImport =
                fields.stream()
                     .filter(f -> f != null && !f.isNameField())
                     .anyMatch(GeneratedFieldModel::renderAsReference);
        boolean needsInlineImport =
                fields.stream()
                     .filter(f -> f != null && !f.isNameField())
                     .anyMatch(GeneratedFieldModel::renderInline);

        if (needsReferenceImport) {
            sb.append("import objectview.annotations.Reference;\n\n");
        }
        if (needsInlineImport) {
            sb.append("import objectview.annotations.Inline;\n\n");
        }

        // All generated classes extend the neutral GeneratedEntity carrier, which
        // holds only results. Where the instance came from (entity QID vs statement
        // GUID) is curation history, not state on the base class.
        sb.append("public class ").append(className)
          .append(" extends quiz.source.GeneratedEntity {\n\n");

        if (model.hasBase()) {
            sb.append("    // extends ").append(model.baseClassName())
              .append(" — base fields below are inherited (flattened in;\n")
              .append("    // the generated runtime compiles each class standalone).\n");
        }

        for (GeneratedFieldModel field : fields) {
            if (field == null || field.isNameField()) {
                continue;
            }

            if (!ownNames.contains(field.name())) {
                sb.append("    // inherited from ").append(model.baseClassName()).append("\n");
            }

            if (field.renderAsReference()) {
                sb.append("    @Reference\n");
            } else if (field.renderInline()) {
                sb.append("    @Inline\n");
            }
            // Quantity fields sort by their leading number, not lexically.
            if (effectiveType(field) == FieldType.NUMBER) {
                sb.append("    @objectview.annotations.Numeric\n");
            }

            sb.append("    public ")
              .append(javaType(field, model, project))
              .append(" ")
              .append(sanitizeFieldName(field.name()));

            if (field.cardinality() == FieldCardinality.COLLECTION) {
                sb.append(" = new java.util.ArrayList<>()");
            } else if (effectiveType(field) == FieldType.STRING
                    || effectiveType(field) == FieldType.TEXT
                    || effectiveType(field) == FieldType.AUTO) {
                sb.append(" = \"\"");
            }

            sb.append(";\n");
        }

        sb.append("\n");
        sb.append("    public ").append(className).append("() {}\n\n");

        sb.append("    @Override\n")
          .append("    public String toString() { return getDisplayName(); }\n");

        sb.append("}\n");
        return sb.toString();
    }

    public String javaType(
            GeneratedFieldModel field,
            GeneratedClassModel owner) {
        return javaType(field, owner, null);
    }

    public String javaType(
            GeneratedFieldModel field,
            GeneratedClassModel owner,
            GeneratedProjectModel project) {

        String base =
                switch (effectiveType(field)) {
                    case IMAGE -> "objectview.media.ImagePane";
                    // quiz.Quantity carries the unit (e.g. "1538 K") yet sorts
                    // numerically; dimensionless numbers render as the bare value.
                    case NUMBER -> "quiz.Quantity";
                    case DATE, STRING, TEXT, AUTO -> "String";
                    case BOOLEAN -> "Boolean";
                    case ENTITY -> objectType(field, owner, project);
                };

        return field.cardinality() == FieldCardinality.COLLECTION
                ? "java.util.List<" + base + ">"
                : base;
    }

    private FieldType effectiveType(GeneratedFieldModel field) {
        String pid =
                field.mapping() == null ? "" : field.mapping().propertyPid();

        if ("P18".equals(pid) || "P242".equals(pid)) {
            return FieldType.IMAGE;
        }

        return field.type();
    }

    private String objectType(
            GeneratedFieldModel field,
            GeneratedClassModel owner,
            GeneratedProjectModel project) {

        String ownerClass = sanitizeClassName(owner.className());
        String type = field.entityClassName();

        // Self-reference (e.g. "neighbours" -> the same class): the owner type
        // is generated, so we can name it directly.
        if (type != null && !type.isBlank()
                && sanitizeClassName(type).equals(ownerClass)) {
            return ownerClass;
        }
        if (field.name().toLowerCase().contains("neigh")) {
            return ownerClass;
        }

        // A cross-reference to another class IN THIS PROJECT: the whole domain
        // is compiled together in one package, so name the target class directly
        // (e.g. an Episode's "characters" -> Character). This is what lets
        // ViewableFieldPaths recurse into the referenced class's fields for
        // nested search/sort/config.
        if (project != null && type != null && !type.isBlank()) {
            GeneratedClassModel target = project.findClass(type);
            // A referenced-only target is a semantic ROLE, not the final entity
            // kind. Kind classification may replace Nominee with Person, Film, …;
            // the field must therefore accept the resulting shared Viewable rather
            // than freezing the role class into its Java signature.
            if (target != null
                    && wikidata.explore.model.MembershipPattern.of(target, project)
                    == wikidata.explore.model.MembershipPattern.REFERENCED
                    && !project.entityKindRules().isEmpty()) {
                return "objectview.Viewable";
            }
            String sanitized = sanitizeClassName(type);
            for (GeneratedClassModel cls : project.classes()) {
                if (cls != null
                        && sanitizeClassName(cls.className()).equals(sanitized)) {
                    return sanitized;
                }
            }
        }

        // Any other entity reference (e.g. a constellation's hemisphere or
        // namesake) points at a wikidata entity that has no class of its own —
        // type it as Viewable so it compiles and still renders as a linked
        // reference (and maps to a raw object, a navigation target).
        return "objectview.Viewable";
    }

    public static String sanitizeClassName(String s) {
        if (s == null || s.isBlank()) {
            return "GeneratedItem";
        }

        StringBuilder out = new StringBuilder();

        for (String part : splitWords(s)) {
            if (part.isBlank()) {
                continue;
            }

            out.append(Character.toUpperCase(part.charAt(0)));

            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }

        if (out.isEmpty()) {
            out.append("GeneratedItem");
        }

        if (Character.isDigit(out.charAt(0))) {
            out.insert(0, "Generated");
        }

        return out.toString();
    }

    public static String sanitizeFieldName(String s) {
        if (s == null || s.isBlank()) {
            return "field";
        }

        List<String> parts = splitWords(s);

        if (parts.isEmpty()) {
            return "field";
        }

        String first = parts.getFirst();
        // A model field may already be a Java-style name (structuredName).  Lowering
        // the whole first token produced `structuredname`, while every model/schema
        // path continued to say `structuredName`; reflective path reads then quietly
        // missed the generated field.  Only decapitalize the identifier's first
        // character.  Labels split into several words still become normal camelCase.
        String initial = first.chars().allMatch(ch -> !Character.isLetter(ch)
                || Character.isUpperCase(ch))
                ? first.toLowerCase(java.util.Locale.ROOT)
                : Character.toLowerCase(first.charAt(0)) + first.substring(1);
        StringBuilder out = new StringBuilder(initial);

        for (int i = 1; i < parts.size(); i++) {
            String p = parts.get(i);

            if (p.isBlank()) {
                continue;
            }

            out.append(Character.toUpperCase(p.charAt(0)));

            if (p.length() > 1) {
                out.append(p.substring(1));
            }
        }

        if (out.isEmpty()) {
            out.append("field");
        }

        if (Character.isDigit(out.charAt(0))) {
            out.insert(0, "f");
        }

        String name = out.toString();

        return switch (name) {
            case "class", "public", "private", "void" -> name + "Field";
            default -> name;
        };
    }

    private static String singularClassName(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return "GeneratedItem";
        }

        String s = fieldName.trim();

        if (s.endsWith("ies") && s.length() > 3) {
            return s.substring(0, s.length() - 3) + "y";
        }

        if (s.endsWith("s") && s.length() > 1) {
            return s.substring(0, s.length() - 1);
        }

        return s;
    }

    private static List<String> splitWords(String s) {
        List<String> out = new ArrayList<>();

        if (s == null) {
            return out;
        }

        String normalized =
                s.replaceAll("[^A-Za-z0-9]+", " ").trim();

        for (String p : normalized.split("\\s+")) {
            if (!p.isBlank()) {
                out.add(p);
            }
        }

        return out;
    }
}
