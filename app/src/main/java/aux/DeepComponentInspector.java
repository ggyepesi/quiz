package aux;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;

/* Debugging utils, keep it even if it is not used. */
public class DeepComponentInspector extends MouseAdapter {

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() != 2) {
            return;
        }

        Component c = e.getComponent();

        JTextArea area = new JTextArea();
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setEditable(false);

        while (c != null) {
            inspectComponent(area, c);
            c = c.getParent();
        }

        JFrame frame = new JFrame("Deep Component Inspector");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.add(new JScrollPane(area));
        frame.setSize(1000, 700);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void inspectComponent(JTextArea area, Component c) {
        area.append("=== " + c.getClass().getName() + " ===\n");

        // Standard API info (important for layout bugs)
        area.append("bounds: " + c.getBounds() + "\n");
        area.append("size: " + c.getSize() + "\n");
        area.append("preferred: " + c.getPreferredSize() + "\n");
        area.append("minimum: " + c.getMinimumSize() + "\n");
        area.append("maximum: " + c.getMaximumSize() + "\n");
        area.append("visible: " + c.isVisible() + "\n");
        area.append("showing: " + c.isShowing() + "\n");

        if (c.getParent() != null) {
            area.append("parent: " + c.getParent().getClass().getName() + "\n");
            area.append("parent layout: " + c.getParent().getLayout() + "\n");
        }

        // 🔥 Reflection: ALL declared fields up the class hierarchy
        Class<?> cls = c.getClass();

        while (cls != null) {
            area.append("-- fields from " + cls.getName() + " --\n");

            for (Field f : cls.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(c);
                    area.append("  " + f.getName() + " = " + safeToString(val) + "\n");
                } catch (Exception ex) {
                    area.append("  " + f.getName() + " = <inaccessible>\n");
                }
            }

            cls = cls.getSuperclass();
        }

        // GridBag info (VERY useful)
        Container parent = c.getParent();
        if (parent != null && parent.getLayout() instanceof GridBagLayout gbl) {
            GridBagConstraints gbc = gbl.getConstraints(c);
            area.append("-- GridBagConstraints --\n");
            area.append("  gridx/y: " + gbc.gridx + "/" + gbc.gridy + "\n");
            area.append("  weightx/y: " + gbc.weightx + "/" + gbc.weighty + "\n");
            area.append("  fill: " + gbc.fill + "\n");
            area.append("  anchor: " + gbc.anchor + "\n");
            area.append("  insets: " + gbc.insets + "\n");
        }

        area.append("\n");
    }

    private String safeToString(Object o) {
        if (o == null) return "null";
        if (o.getClass().isArray()) return "[array]";
        return String.valueOf(o);
    }

    public static void installRecursively(Component c) {
        DeepComponentInspector listener = new DeepComponentInspector();
        install(c, listener);
    }

    private static void install(Component c, DeepComponentInspector l) {
        c.addMouseListener(l);

        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                install(child, l);
            }
        }
    }
}