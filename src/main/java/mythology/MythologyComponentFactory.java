package mythology;
import java.awt.TextField;
import java.awt.event.MouseAdapter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.border.TitledBorder;

public class MythologyComponentFactory {
    private static Map<MythologyEntity, JFrame> frames = new HashMap<>();

    // Adds a TextField to mainPanel for entity with title. The text is the name of entity,
    // double-click shows the frame of it.
    static void addField(JPanel mainPanel, String title, MythologyEntity entity) {
        JPanel panel = new JPanel();
        mainPanel.add(panel);
        panel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder (),
                                                        title,
                                                        TitledBorder.CENTER,
                                                        TitledBorder.TOP));
        TextField field = new TextField(entity.getName());
        field.addMouseListener(new MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() != 2) return;
                createFrame(entity);
            }
        });
        panel.add(field);
    }

    static void addList(JPanel mainPanel, String title, List<? extends MythologyEntity> entities) {
        addList(mainPanel, title, entities, new ArrayList<>(), new ArrayList<>());
    }

    static void addList(JPanel mainPanel, String title, List<? extends MythologyEntity> entities,
                        List<String> fieldsToAdd, List<String> fieldsToOmit) {
            // Adds a List to mainPanel for the names of entities with title. Double-click on a name shows the frame of the entity.
        if (entities.isEmpty()) return;
        JPanel panel = new JPanel();
        mainPanel.add(panel);
        System.out.println("addList " + title);
        panel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(),
                                                        title,
                                                        TitledBorder.CENTER,
                                                        TitledBorder.TOP));
        DefaultListModel<String> model = new DefaultListModel<>();
        for (MythologyEntity entity : entities) {
            model.addElement(entity.getName());
        }
        JList<String> list = new JList<>(model);
        list.addMouseListener(new MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() != 2) return;
                int index = list.locationToIndex(evt.getPoint());
                if (index == -1) return;
                System.out.println("List item " + index + ", " + entities.get(index).getName() + ", " +
                                    entities.get(index) + ", " + evt.getClickCount());
                createFrame(entities.get(index));
            }
        });
        JScrollPane listScrollPane = new JScrollPane();
        listScrollPane.setViewportView(list);
        list.setLayoutOrientation(JList.VERTICAL);
        list.setSize(400, 200);
        panel.setSize(400, 200);
        panel.add(listScrollPane);
    }

    // Adds a Table to mainPanel for the relations with title. The row for a relation shows the names of its entity
    // arguments and the relation name.
    // Double-click on a name shows the frame of the entity.
    static <E extends MythologyEntity> void addTable(JPanel mainPanel, String title, Class<E> classOfItems, List<E> entities) {
        if (entities.isEmpty()) return;
        JPanel panel = new JPanel();
        mainPanel.add(panel);
        panel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder (),
                                                        title,
                                                        TitledBorder.CENTER,
                                                        TitledBorder.TOP));
        // Change this to fields per entoty if needed.
        List<Field> fields = getFields(classOfItems);
        String[] columnNames = new String[fields.size()];
        String[][] rows = new String[entities.size()][fields.size()];
        for (int i  = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            columnNames[i] = f.getName();
            f.setAccessible(true);
            int j = 0;
            for (E entity : entities) {
                try {
                    Object o = f.get(entity);
                    if (o instanceof MythologyEntity) {
                        rows[j][i] = ((MythologyEntity)o).getName();
                    } else {
                        rows[j][i] = o.toString(); // name of the Relation
                    }
                } catch (IllegalAccessException exception) {
                    exception.printStackTrace();
                }
                ++j;
            }
        }
        // No cell is editable
        JTable table = new JTable(rows, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table.addMouseListener(new MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() != 2) return;
                int row = table.getSelectedRow();
                int column = table.getSelectedColumn();
                // If the column-th field of the row-th object is an entity then create frame for it.
                MythologyEntity entity = entities.get(row);
                try {
                    Object o = fields.get(column).get(entity);
                    if (o instanceof MythologyEntity) {
                        createFrame((MythologyEntity)o);
                    }
                } catch (IllegalAccessException exception) {
                    exception.printStackTrace();
                }
            }
        });
        JScrollPane listScrollPane = new JScrollPane();
        listScrollPane.setViewportView(table);
        table.setSize(400, 200);

        panel.setSize(400, 200);
        panel.add(table);
    }

    static void createFrame(MythologyEntity entity) {
        JFrame frame = frames.get(entity);
        if (frame != null) {
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
            return;
        }
        frame = new JFrame(entity.getName());
        frames.put(entity, frame);
        try {
            frame.add(entity.getComponent());
        } catch (Exception e) {
            e.printStackTrace();
        }
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                frames.remove(entity);
            }
        });
        frame.setLocationRelativeTo(null);
        frame.setResizable(true);
        frame.pack();
        frame.setVisible(true); 
    }

    static List<Field> getFields(Class<? extends Object> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != Object.class) {
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }
}

