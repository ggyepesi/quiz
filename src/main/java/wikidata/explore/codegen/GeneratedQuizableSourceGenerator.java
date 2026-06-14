package wikidata.explore.codegen;

import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;

import java.util.ArrayList;
import java.util.List;

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
        String className = sanitizeClassName(model.className());

        StringBuilder sb = new StringBuilder();

        sb.append("package ").append(packageName).append(";\n\n");

        boolean needsReferenceImport =
                model.fields().stream()
                     .filter(f -> f != null && !f.isNameField())
                     .anyMatch(GeneratedFieldModel::renderAsReference);

        if (needsReferenceImport) {
            sb.append("import quiz.annotations.QuizableReference;\n\n");
        }

        sb.append("public class ").append(className)
          .append(" extends quiz.QuizableAdapter {\n\n");

        sb.append("    public String qid = \"\";\n");
        sb.append("    @quiz.annotations.Link\n");
        sb.append("    public String wikidataUrl = \"\";\n");
        sb.append("    public String name = \"\";\n\n");

        for (GeneratedFieldModel field : model.fields()) {
            if (field == null || field.isNameField()) {
                continue;
            }

            if (field.renderAsReference()) {
                sb.append("    @QuizableReference\n");
            }

            sb.append("    public ")
              .append(javaType(field, model))
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

        String base =
                switch (effectiveType(field)) {
                    case IMAGE -> "quiz.ui.ImagePane";
                    case NUMBER -> "Double";
                    case DATE, STRING, TEXT, AUTO -> "String";
                    case ENTITY -> objectType(field, owner);
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
            GeneratedClassModel owner) {

        String type = field.entityClassName();

        if (type == null || type.isBlank()) {
            if (field.name().toLowerCase().contains("neigh")) {
                type = owner.className();
            } else {
                type = singularClassName(field.name());
            }
        }

        return sanitizeClassName(type);
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