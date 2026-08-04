package mythology;

import java.awt.Component;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;

import quiz.source.ManualEntity;

public  class NamedEntity extends ManualEntity implements MythologyEntity {
    private String name;

    protected NamedEntity() {}

    public NamedEntity(String name) {
        this.name = name;
    }

    @Override
    public String getIdentifier() { return name; }

    @Override
    public String getDisplayName() { return name; }

    @Override
    // Returns the name of all fields except name
    public Component getComponent() throws Exception {
        List<String> fieldNamesToOmit = new ArrayList<>();
        fieldNamesToOmit.add("name");
        return getComponent(fieldNamesToOmit);
    }

    public Component getComponent(List<String> fieldNamesToOmit) throws Exception {
        Method fieldMethod = MythologyComponentFactory.class.getDeclaredMethod(
        "addField", JPanel.class, String.class, MythologyEntity.class);
        Method listMethod = MythologyComponentFactory.class.getDeclaredMethod(
            "addList", JPanel.class, String.class, List.class);
        Method tableMethod = MythologyComponentFactory.class.getDeclaredMethod(
            "addTable", JPanel.class, String.class, Class.class, List.class);
            List<Field> fields = MythologyComponentFactory.getFields(getClass());
        JPanel panel = new JPanel();
    
        for (Field f : fields) {
            if (fieldNamesToOmit.contains(f.getName())) continue;
            f.setAccessible(true);
            Object o = f.get(this);
            if (o == null) continue;
            if (MythologyEntity.class.isInstance(o)) {
                fieldMethod.invoke(null, panel, f.getName(), o);
            }
            if (List.class.isInstance(o)) {
                // Change this to check if member type is Relation.
                if (f.getName().equals("deeds")) {
                    tableMethod.invoke(null, panel, f.getName(), Deed.class, o);
                    continue;
                }
                System.out.println("Field " + f.getName() +  " of entity [" + name + "] " + " is a list");
                listMethod.invoke(null, panel, f.getName(), o);
            }
        }
        return panel;
    }

}
