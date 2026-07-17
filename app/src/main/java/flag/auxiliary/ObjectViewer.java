package flag.auxiliary;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.Collection;

/**
 * A utility class that creates a JFrame to display the fields and values of any given object using Java Reflection.
 * This version supports a recursive view into nested objects and collections, including Image objects.
 */
public class ObjectViewer {

    /**
     * Constructs the ObjectViewer.
     *
     * @param object The object to be inspected and displayed.
     */
    public ObjectViewer(Object object) {
        // Create the main JFrame
        JFrame frame = new JFrame("Object Content Viewer: " + object.getClass().getSimpleName());
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(600, 400);
        frame.setLocationRelativeTo(null); // Center the frame

        // Define the table model with column headers
        DefaultTableModel model = new DefaultTableModel();
        model.addColumn("Field Name");
        model.addColumn("Value");
        model.addColumn("Type");

        // Start the recursive exploration
        exploreFields(object, model, 0);

        // Create the JTable using the populated model
        JTable table = new JTable(model);
        // Set column widths to improve readability
        table.getColumnModel().getColumn(0).setPreferredWidth(150);
        table.getColumnModel().getColumn(1).setPreferredWidth(250);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);

        // Add the table to a JScrollPane to make it scrollable
        JScrollPane scrollPane = new JScrollPane(table);

        // Add the scroll pane to the frame's content pane
        frame.add(scrollPane, BorderLayout.CENTER);

        // Make the frame visible
        frame.setVisible(true);
    }

    /**
     * Recursively explores the fields of an object and adds them to the JTable model.
     *
     * @param object The object to explore.
     * @param model  The table model to add rows to.
     * @param level  The current recursion level for indentation.
     */
    private void exploreFields(Object object, DefaultTableModel model, int level) {
        if (object == null) {
            return;
        }

        // Create a prefix for indentation
        String indent = "  ".repeat(Math.max(0, level));
        try {
            // Get all declared fields for the current object's class
            Field[] fields = object.getClass().getDeclaredFields();


            // Iterate over each field
            for (Field field : fields) {
                // To access private fields, we must set them to be accessible
                field.setAccessible(true);

                String fieldName = field.getName();
                Object value = field.get(object);
                Class<?> type = field.getType();

                // Check for a specific Image type
                if (Image.class.isAssignableFrom(type)) {
                    model.addRow(new Object[]{indent + fieldName, "Image Object", type.getSimpleName()});
                }
                // Check if the type is a primitive, primitive wrapper, or String
                else if (type.isPrimitive() || type.equals(String.class) || isWrapperType(type)) {
                    model.addRow(new Object[]{indent + fieldName, value, type.getSimpleName()});
                } else if (Collection.class.isAssignableFrom(type)) {
                    // Handle collections
                    model.addRow(new Object[]{indent + fieldName, "Collection", type.getSimpleName()});
                    if (value instanceof Collection) {
                        int i = 0;
                        for (Object item : (Collection<?>) value) {
                            // Check if the collection item is a simple type
                            if (item != null && (item.getClass().isPrimitive() || item.getClass().equals(String.class) || isWrapperType(item.getClass()))) {
                                model.addRow(new Object[]{indent + "  [" + i + "]", item, item.getClass().getSimpleName()});
                            } else {
                                // Recursively explore complex collection items
                                model.addRow(new Object[]{indent + "  [" + i + "]", "Object", (item != null ? item.getClass().getSimpleName() : "null")});
                                exploreFields(item, model, level + 2);
                            }
                            i++;
                        }
                    }
                } else {
                    // For complex objects, add a row and then recurse
                    model.addRow(new Object[]{indent + fieldName, value, type.getSimpleName()});
                    exploreFields(value, model, level + 1);
                }
            }
        } catch (IllegalAccessException e) {
            model.addRow(new Object[]{indent + "Error", e.getMessage(), "Exception"});
        }
    }

    /**
     * Checks if a given class is a primitive wrapper type.
     *
     * @param type The class to check.
     * @return true if the class is a primitive wrapper, false otherwise.
     */
    private boolean isWrapperType(Class<?> type) {
        return type.equals(Boolean.class) ||
               type.equals(Character.class) ||
               type.equals(Byte.class) ||
               type.equals(Short.class) ||
               type.equals(Integer.class) ||
               type.equals(Long.class) ||
               type.equals(Float.class) ||
               type.equals(Double.class);
    }

    /**
     * A simple inner class to demonstrate the functionality of ObjectViewer, with nested objects and a list.
     */
    public static class SampleData {
        private String name;
        private int age;
        public Address address;
        private final java.util.List<String> hobbies;
        private Image profilePicture;

        public SampleData(String name, int age, Address address, java.util.List<String> hobbies, Image profilePicture) {
            this.name = name;
            this.age = age;
            this.address = address;
            this.hobbies = hobbies;
            this.profilePicture = profilePicture;
        }
    }

    /**
     * A nested class to demonstrate recursion.
     */
    public static class Address {
        private String street;
        private String city;
        private int zipCode;

        public Address(String street, String city, int zipCode) {
            this.street = street;
            this.city = city;
            this.zipCode = zipCode;
        }
    }

    /**
     * Main method to run the demonstration.
     * It creates an instance of the SampleData class with nested data and displays its content.
     */
    public static void main(String[] args) {
        // Create an instance of our sample data class with nested objects, a list, and an Image
        Address homeAddress = new Address("123 Main St", "Anytown", 12345);
        java.util.List<String> hobbiesList = java.util.Arrays.asList("Reading", "Hiking", "Coding");
        // Create a simple blank image for the example
        Image blankImage = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        SampleData person = new SampleData("Alice", 25, homeAddress, hobbiesList, blankImage);

        // Create an instance of ObjectViewer to display the 'person' object
        new ObjectViewer(person);
    }
}
