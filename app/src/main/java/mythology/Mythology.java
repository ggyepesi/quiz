package mythology;

import java.util.Map;
import java.util.Map.Entry;

import java.util.TreeMap;

public class Mythology {
    private Map<String, Creature> creatures = new TreeMap<>();
    private Map<String, EntityGroup> groups = new TreeMap<>();
    private Map<String, Attribute> attributes = new TreeMap<>();
    private Map<String, Map<String, Deed>> deeds = new TreeMap<>();

    public Attribute getAttribute(String name) {
        return getEntity(attributes, name, new Attribute(name));
    }

    public Creature getCreature(String name) {
        return getEntity(creatures, name, new Creature(name));
    }

    public EntityGroup getGroup(String name) {
        return getEntity(groups, name, new EntityGroup(name));
    }

    public Map<String, Creature> getCreatures() {
        return creatures;
    }

    public Map<String, EntityGroup> getGroups() {
        return groups;
    }

    public Map<String, Attribute> getAttributes() {
        return attributes;
    }

    public Map<String, Map<String, Deed>> getDeeds() {
        return deeds;
    }

    public void addDeed(Deed deed) {
        Map<String, Deed> specificDeeds = deeds.get(deed.getName());
        if (specificDeeds == null) {
            specificDeeds = new TreeMap<>();
            deeds.put(deed.getName(), specificDeeds);
        }
        specificDeeds.put(deed.getSubject() + ", " + deed.getObject(), deed);
    }

    private void addSibling(Creature creature, Creature sibling) {
        for (Creature c : creature.getSiblings()) {
            if (c.getName().equals(sibling.getName())) return;
        }
        creature.getSiblings().add(sibling);
    }

    private <E extends NamedEntity>E getEntity(Map<String, E> entities, String name, E defaultValue) {
        return entities.computeIfAbsent(name, c -> defaultValue);
    }

    // add missing relations like siblings, deeds (kill X, raised_by, etc.) etc.
    public void curate() {
        for (Entry<String, Creature> entry : creatures.entrySet()) {
            Creature c = entry.getValue();
            for (Entry<String, Creature> entry1 : creatures.entrySet()) {
                Creature c1 = entry1.getValue();
                if (c == c1) continue;
                
                if ((c.getFather() != null && c.getFather() == c1.getFather()) ||
                    (c.getMother() != null && c.getMother() == c1.getMother())    ) {
                        addSibling(c, c1);
                        addSibling(c1, c);
                }
            }
        }
    }
}
