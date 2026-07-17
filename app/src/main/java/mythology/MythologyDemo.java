package mythology;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.swing.JFrame;
import javax.swing.JPanel;

public class MythologyDemo {
    public void show(Mythology mythology) throws Exception {
        JFrame frame = new JFrame("Greek mythology");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel mainPanel = new JPanel();
        frame.add(mainPanel);
    
        List<MythologyEntity> creatures = new ArrayList<>();
        for (Map.Entry<String, Creature> entry : mythology.getCreatures().entrySet()) {
            creatures.add(entry.getValue());
        }
        
        List<MythologyEntity> groups = new ArrayList<>();
        for (Map.Entry<String, EntityGroup> entry : mythology.getGroups().entrySet()) {
            groups.add(entry.getValue());
        }

        MythologyComponentFactory.addList(mainPanel, "Creatures", creatures);
        MythologyComponentFactory.addList(mainPanel, "Groups", groups);

        List<Deed> deeds = new ArrayList<>();
        for (Entry<String, Map<String, Deed>> ds :  mythology.getDeeds().entrySet()) {
            for (Entry<String, Deed> deed : ds.getValue().entrySet()) {
                deeds.add(deed.getValue());
            }
        }

        MythologyComponentFactory.addTable(mainPanel, "Deeds", Deed.class, deeds);

        frame.setLocationRelativeTo(null);
        frame.setResizable(true);
        frame.pack();
        frame.setVisible(true); 
    }
}
