package org.formstamper.core.engine;

import quiz.QuizableFieldPaths.FieldPath;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

public class PathDrivenFormCompiler {

    public void compileFromPaths(Class<?> modelClass, List<FieldPath> paths, String targetPackage, File outputRootDir) throws IOException {
        String modelClassName = modelClass.getSimpleName();
        String viewClassName = modelClassName + "FormView";

        // Ensure correct package folder directory structure exists
        File packageDir = new File(outputRootDir, targetPackage.replace('.', '/'));
        if (!packageDir.exists()) {
            packageDir.mkdirs();
        }

        File javaFile = new File(packageDir, viewClassName + ".java");

        // Group paths by root container to unroll structural layouts cleanly
        Map<String, List<FieldPath>> structuralGroups = new LinkedHashMap<>();
        for (FieldPath fp : paths) {
            String root = fp.path().get(0);
            structuralGroups.computeIfAbsent(root, k -> new ArrayList<>()).add(fp);
        }

        try (PrintWriter out = new PrintWriter(new FileWriter(javaFile))) {
            // 1. Package and Imports
            out.printf("package %s;\n\n", targetPackage);
            out.printf("import %s;\n", modelClass.getName());
            out.println("import javax.swing.*;");
            out.println("import java.awt.*;");
            out.println("import java.util.List;");
            out.println("import java.util.Map;\n");

            // 2. Class Declaration
            out.printf("public final class %s extends JPanel {\n\n", viewClassName);

            // 3. Constructor
            out.printf("    public %s(%s data) {\n", viewClassName, modelClassName);
            out.println("        this.setLayout(new GridBagLayout());");
            out.println("        this.setOpaque(false);");
            out.println("        this.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));");
            out.println("        if (data == null) return;\n");
            out.println("        GridBagConstraints gbc = new GridBagConstraints();");
            out.println("        gbc.fill = GridBagConstraints.HORIZONTAL;");
            out.println("        gbc.weightx = 1.0;");
            out.println("        int mainRow = 0;\n");

            // 4. Code Generation Loop (Unrolling the structure)
            for (Map.Entry<String, List<FieldPath>> entry : structuralGroups.entrySet()) {
                String rootKey = entry.getKey();
                List<FieldPath> groupPaths = entry.getValue();

                // Case A: Flat Primitive Fields / Single Leaf Values
                if (groupPaths.size() == 1 && groupPaths.get(0).path().size() == 1) {
                    out.printf("        // Primitive / Text Field: %s\n", rootKey);
                    String getter = resolveGetter(modelClass, rootKey);

                    if (isBoolean(modelClass, rootKey)) {
                        out.printf("        this.add(createValuePanel(\"%s\", String.valueOf(data.%s())), layoutConstraints(mainRow++));\n\n", rootKey, getter);
                    } else if (isStringField(modelClass, rootKey)) {
                        out.printf("        if (data.%s() != null && !data.%s().trim().isEmpty()) {\n", getter, getter);
                        out.printf("            this.add(createValuePanel(\"%s\", data.%s()), layoutConstraints(mainRow++));\n", rootKey, getter);
                        out.println("        }\n");
                    } else {
                        out.printf("        if (data.%s() != 0) {\n", getter);
                        out.printf("            this.add(createValuePanel(\"%s\", String.valueOf(data.%s())), layoutConstraints(mainRow++));\n", rootKey, getter);
                        out.println("        }\n");
                    }
                }
                // Case B: Nested Object Layout Group
                else {
                    out.printf("        // Structural Composite Sub-Panel: %s\n", rootKey);
                    String rootGetter = resolveGetter(modelClass, rootKey);

                    out.printf("        if (data.%s() != null) {\n", rootGetter);
                    out.println("            JPanel subPanel = new JPanel(new GridBagLayout());");
                    out.println("            subPanel.setOpaque(false);");
                    out.printf("            subPanel.setBorder(BorderFactory.createTitledBorder(\"%s\"));\n", rootKey);
                    out.println("            GridBagConstraints subGbc = new GridBagConstraints();");
                    out.println("            subGbc.fill = GridBagConstraints.HORIZONTAL;");
                    out.println("            subGbc.weightx = 1.0;");
                    out.println("            int subRow = 0;");
                    out.println("            boolean hasAddedAnyLeaf = false;\n");

                    // Loop inner nested leaves
                    for (FieldPath fp : groupPaths) {
                        String leafKey = fp.path().get(1);
                        out.printf("            // Leaf: %s.%s\n", rootKey, leafKey);

                        if ("url".equals(leafKey)) {
                            out.printf("            if (data.%s().getQid() != null && !data.%s().getQid().isEmpty()) {\n", rootGetter, rootGetter);
                            out.println("                subGbc.gridy = subRow++;");
                            out.printf("                subPanel.add(createValuePanel(\"%s\", \"https://www.wikidata.org/wiki/\" + data.%s().getQid()), subGbc);\n", fp.title(), rootGetter);
                            out.println("                hasAddedAnyLeaf = true;");
                            out.println("            }");
                        } else {
                            String leafGetter = "qid".equals(leafKey) ? "getQid" : "getName";
                            out.printf("            if (data.%s().%s() != null && !data.%s().%s().trim().isEmpty()) {\n", rootGetter, leafGetter, rootGetter, leafGetter);
                            out.println("                subGbc.gridy = subRow++;");
                            out.printf("                subPanel.add(createValuePanel(\"%s\", data.%s().%s()), subGbc);\n", fp.title(), rootGetter, leafGetter);
                            out.println("                hasAddedAnyLeaf = true;");
                            out.println("            }");
                        }
                    }

                    out.println("\n            if (hasAddedAnyLeaf) {");
                    out.println("                gbc.gridy = mainRow++;");
                    out.println("                this.add(subPanel, gbc);");
                    out.println("            }");
                    out.println("        }\n");
                }
            }

            out.println("    }\n");

            // 5. Append Swing Generation Runtime Helper Methods
            appendHelpers(out);

            // Close Class
            out.println("}");
        }
    }

    private String resolveGetter(Class<?> clazz, String fieldName) {
        if ("winner".equals(fieldName)) return "isWinner";
        return "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    private boolean isBoolean(Class<?> clazz, String fieldName) {
        return "winner".equals(fieldName);
    }

    private boolean isStringField(Class<?> clazz, String fieldName) {
        try {
            String getterName = resolveGetter(clazz, fieldName);
            return clazz.getMethod(getterName).getReturnType() == String.class;
        } catch (NoSuchMethodException e) {
            try {
                return clazz.getDeclaredField(fieldName).getType() == String.class;
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    private void appendHelpers(PrintWriter out) {
        out.println("    private JPanel createValuePanel(String title, String value) {");
        out.println("        JPanel p = new JPanel(new BorderLayout());");
        out.println("        p.setOpaque(false);");
        out.println("        p.setBorder(BorderFactory.createTitledBorder(title));");
        out.println("        JLabel label = new JLabel(value);");
        out.println("        label.setForeground(new Color(0, 80, 180));");
        out.println("        p.add(label, BorderLayout.WEST);");
        out.println("        return p;");
        out.println("    }\n");
        out.println("    private GridBagConstraints layoutConstraints(int y) {");
        out.println("        GridBagConstraints gbc = new GridBagConstraints();");
        out.println("        gbc.gridy = y;");
        out.println("        gbc.fill = GridBagConstraints.HORIZONTAL;");
        out.println("        gbc.weightx = 1.0;");
        out.println("        gbc.insets = new Insets(4, 4, 4, 4);");
        out.println("        return gbc;");
        out.println("    }");
    }
}