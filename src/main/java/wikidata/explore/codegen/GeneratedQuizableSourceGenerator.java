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

public class GeneratedQuizableSourceGenerator {
    public static final String GENERATED_PACKAGE = "wikidata.generated";

    private final String packageName;

    public GeneratedQuizableSourceGenerator(String packageName) {
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

        if (needsReferenceImport) {
            sb.append("import quiz.annotations.QuizableReference;\n\n");
        }

        sb.append("public class ").append(className)
          .append(" extends quiz.QuizableAdapter {\n\n");

        if (model.hasBase()) {
            sb.append("    // extends ").append(model.baseClassName())
              .append(" — base fields below are inherited (flattened in;\n")
              .append("    // the generated runtime compiles each class standalone).\n");
        }

        // QID stays the identity (getIdentifier), but the raw QID/URL are
        // hidden from the card (@NotQuizableField) and surfaced together as one
        // collapsed "source" chip below — mirrors WikidataDynamicObject so typed
        // and dynamic instances render the same.
        sb.append("    @quiz.annotations.NotQuizableField\n");
        sb.append("    public String qid = \"\";\n");
        sb.append("    @quiz.annotations.NotQuizableField\n");
        sb.append("    @quiz.annotations.Link\n");
        sb.append("    public String wikidataUrl = \"\";\n");
        // Identity/display name = the card TITLE, re-injected once as an identity
        // field by getConfigurableFields; without @NotQuizableField it also leaks
        // into getAllFields and shows up TWICE in sort/search/viewconfig.
        sb.append("    @quiz.annotations.NotQuizableField\n");
        sb.append("    public String name = \"\";\n\n");

        for (GeneratedFieldModel field : fields) {
            if (field == null || field.isNameField()) {
                continue;
            }

            if (!ownNames.contains(field.name())) {
                sb.append("    // inherited from ").append(model.baseClassName()).append("\n");
            }

            if (field.renderAsReference()) {
                sb.append("    @QuizableReference\n");
            }
            // Quantity fields sort by their leading number, not lexically.
            if (effectiveType(field) == FieldType.NUMBER) {
                sb.append("    @quiz.annotations.Numeric\n");
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

        // Provenance LAST so it renders as an unobtrusive footer chip below the
        // real fields (the QID/URL above are hidden via @NotQuizableField).
        // @Provenance drives the collapsed-chip rendering and keeps Source out
        // of entity-type grouping. Populated by GeneratedQuizableMapper.
        sb.append("\n");
        sb.append("    @quiz.annotations.Provenance\n");
        sb.append("    @com.fasterxml.jackson.annotation.JsonIgnore\n");
        sb.append("    public quiz.source.Source source;\n");

        sb.append("\n");
        sb.append("    public ").append(className).append("() {}\n\n");

        sb.append("    @Override\n")
          .append("    public String getIdentifier() {\n")
          .append("        return qid == null || qid.isBlank() ? name : qid;\n")
          .append("    }\n\n");

        sb.append("    @Override\n")
          .append("    public String getDisplayName() {\n")
          .append("        return name == null || name.isBlank() ? qid : name;\n")
          .append("    }\n\n");

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
                    case IMAGE -> "quiz.ui.ImagePane";
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
        // QuizableFieldPaths recurse into the referenced class's fields for
        // nested search/sort/config.
        if (project != null && type != null && !type.isBlank()) {
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
        // type it as Quizable so it compiles and still renders as a linked
        // reference (and maps to a raw object, a navigation target).
        return "quiz.Quizable";
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

        StringBuilder out = new StringBuilder(parts.getFirst().toLowerCase());

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