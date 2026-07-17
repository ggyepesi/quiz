package flag;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.lang.reflect.*;
import java.util.*;

public class ObjectInspector {

    public static JComponent inspect(Object obj) {
        return inspect(obj, new HashSet<>(), 0);
    }

    private static JComponent inspect(Object obj, Set<Integer> visited, int depth) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(5, 20 * depth, 5, 5));

        if (obj == null) {
            panel.add(new JLabel("null"));
            return panel;
        }

        Class<?> clazz = obj.getClass();
        int identity = System.identityHashCode(obj);

        // Handle cycles
        if (visited.contains(identity)) {
            panel.add(new JLabel(clazz.getSimpleName() + " {…cyclic reference…}"));
            return panel;
        }
        visited.add(identity);

        // Simple values
        if (isSimpleType(clazz)) {
            panel.add(new JLabel(obj.toString()));
            return panel;
        }

        // Arrays
        if (clazz.isArray()) {
            int length = Array.getLength(obj);
            panel.add(new JLabel(clazz.getComponentType().getSimpleName() + "[] length=" + length));

            for (int i = 0; i < length; i++) {
                Object element = Array.get(obj, i);
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
                row.add(new JLabel("[" + i + "] = "));
                row.add(inspect(element, visited, depth + 1));
                panel.add(row);
            }
            return panel;
        }

        // Collections
        if (obj instanceof Collection<?> col) {
            panel.add(new JLabel(clazz.getSimpleName() + " (Collection) size=" + col.size()));

            int index = 0;
            for (Object element : col) {
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
                row.add(new JLabel("[" + index + "] = "));
                row.add(inspect(element, visited, depth + 1));
                panel.add(row);
                index++;
            }
            return panel;
        }

        // Maps
        if (obj instanceof Map<?, ?> map) {
            panel.add(new JLabel(clazz.getSimpleName() + " (Map) size=" + map.size()));

            for (var entry : map.entrySet()) {
                JPanel row = new JPanel();
                row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));

                row.add(new JLabel("Key:"));
                row.add(inspect(entry.getKey(), visited, depth + 1));

                row.add(new JLabel("Value:"));
                row.add(inspect(entry.getValue(), visited, depth + 1));

                panel.add(row);
            }
            return panel;
        }

        // General object → inspect fields
        panel.add(new JLabel(clazz.getSimpleName() + " {"));

        for (Field field : getAllFields(clazz)) {
            field.setAccessible(true);

            Object value;
            try {
                value = field.get(obj);
            } catch (Exception e) {
                value = "[error: " + e.getMessage() + "]";
            }

            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
            row.add(new JLabel(field.getName() + " = "));
            row.add(inspect(value, visited, depth + 1));
            panel.add(row);
        }

        panel.add(new JLabel("}"));
        return panel;
    }


    // Helpers -------------------------------------------------------

    private static boolean isSimpleType(Class<?> clazz) {
        return clazz.isPrimitive() ||
               clazz == String.class ||
               clazz == Integer.class ||
               clazz == Long.class ||
               clazz == Double.class ||
               clazz == Float.class ||
               clazz == Boolean.class ||
               clazz == Byte.class ||
               clazz == Short.class ||
               clazz == Character.class ||
               clazz.isEnum();
    }

    private static java.util.List<Field> getAllFields(Class<?> clazz) {
        java.util.List<Field> fields = new ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }


    // Demo ----------------------------------------------------------

    public static void main(String[] args) {
        class Address { String city = "Paris"; int zip = 75000; }
        class Person { String name = "Alice"; int age = 30; Address address = new Address(); }

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Object Inspector");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(600, 600);

            Person p = new Person();
            JComponent comp = inspect(p);

            JScrollPane scroll = new JScrollPane(comp);
            frame.add(scroll);
            frame.setVisible(true);
        });
    }
}
