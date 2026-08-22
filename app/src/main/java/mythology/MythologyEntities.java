package mythology;

import java.io.BufferedReader;
import java.util.*;

import aux.Constants;
import quiz.group.ViewableGroup;
import objectview.Viewable;
import objectview.viewconfig.DomainViews;

import static aux.Constants.mythologyDir;

public class MythologyEntities implements DomainViews {
    private final Mythology mythology = new Mythology();

    private final ViewableGroup rootGroup = new ViewableGroup("All");

    public static void main(String[] args) throws Exception {
        MythologyEntities reader = new MythologyEntities();
        reader.buildViews();
        reader.show();
    }

    public Collection<Creature> getCreatures() {
        return mythology.getCreatures().values();
    }

    private void show() throws Exception {
        new MythologyDemo().show(mythology);
    }

    @Override
    public void buildViews() throws Exception {
        BufferedReader reader = Constants.getBufferedReaderForResource(mythologyDir + "greekmythology.txt");
        Stack<LineProcessor> processors = new Stack<>();

        LineProcessor processor = new TypeProcessor(mythology, rootGroup);
        processors.push(processor);
        String line;
        while ((line = reader.readLine()) != null) {
            do {
                // nextProcessor null means process line with previous processor
                LineProcessor nextProcessor = processor.processLine(line);
                while (nextProcessor == null) {
                    processor.done();
                    // Pop the processor which has finished by returning null.
                    processors.pop();
                    // Turn to the previous processor, it will be popped when it will finish.
                    nextProcessor = processors.peek().processLine(line);
                }
                if (nextProcessor != processor) {
                    // Push the new processor, it will be popped when it will finish.
                    processors.push(nextProcessor);
                    processor = nextProcessor;
                    System.out.println("Push " + processor);
                }
            } while (processor == null);
        }
        processor.done();
        reader.close();
        mythology.curate();
        // as groups are defined explicitly, creatures are not added to the root group, add them here
        for (Creature creat : mythology.getCreatures().values()) {
            rootGroup.addMember(creat);
        }
        System.out.println("Groups for " + rootGroup);
    }

    @Override
    public java.util.List<objectview.viewconfig.DomainGroupRoot> getGroupRootBindings() {
        return java.util.List.of(new objectview.viewconfig.DomainGroupRoot(
                Creature.class.getSimpleName(), rootGroup));
    }

    @Override
    public Map<String, ? extends Viewable> getViewables() {
        return mythology.getCreatures();
    }
}

interface LineProcessor {
    LineProcessor processLine(String line);
    void done();
}

abstract class MythologyLineProcessor implements LineProcessor {    
    private final Mythology mythology;

    public MythologyLineProcessor(Mythology mythology) {
        this.mythology = mythology;
    }

    public Mythology getMythology() {
        return mythology;
    }

    public LineProcessor processLine(String line) {
        if (line.trim().isEmpty()) return this;
        String[] tags = line.trim().split("\s+");
        if (tags.length < 2) {
            if (tags.length > 0) {  // skip empty line silently
                System.out.println("Type and name needed: <" + line + ">, " + tags.length + "<" + tags[0] + ">");
            }
            return this;
        }
        return process(tags[0], tags[1]);
    }

    <E extends Enum<E>>E getEnumValue(String name, E[] values) {
        for (E f : values) {
            if (f.toString().toLowerCase().equals(name)) {
                return f;
            }
        }
        return null;
    }

    public abstract LineProcessor process(String name, String value);

    public void done() {}
}

class TypeProcessor extends MythologyLineProcessor {
    static enum Type {
        CREATURE, GROUP, DEED
    }

    private ViewableGroup rootGroup;

    public TypeProcessor(Mythology mythology, ViewableGroup rootGroup) {
        super(mythology);
        this.rootGroup = rootGroup;
    }

    public LineProcessor process(String name, String value) {
        Type type = getEnumValue(name, Type.values());
        if (type == null) {
            System.out.println("No such type " + name);
            return this;
        }
        switch (type) {
            case CREATURE:
                System.out.println("Creature " + value);
                // siblings, etc.
                return new CreatureLineProcessor(getMythology(), value);
            case GROUP:
                // tags, leader, etc.
                System.out.println("Group " + value);
                return new GroupLineProcessor(getMythology(), value, rootGroup);
            case DEED:
                // tags, leader, etc.
                System.out.println("Deed " + value);
                return new DeedLineProcessor(getMythology(), value);
            default:
                System.out.println("No such type " + name);
                return this;
        }
    }
}

class CreatureLineProcessor extends MythologyLineProcessor {
    static enum Field {
        FATHER, MOTHER, SIBLING, CONSORTS, KILL, KILLED_BY, ATTRIBUTE, CHILD
    }

    private final Creature creature;
 
    public CreatureLineProcessor(Mythology mythology, String name) {
        super(mythology);
        this.creature = mythology.getCreature(name);
    }

    public LineProcessor process(String name, String value) {
        Field field = getEnumValue(name, Field.values());
        if (field == null) {
            System.out.println("No such field " + name + ", " + this);
            return null;
        }
        switch (field) {
            case FATHER:
                creature.setFather(getMythology().getCreature(value));
                System.out.println("Father " + value);
                return this;
            case MOTHER:
                creature.setMother(getMythology().getCreature(value));
                return this;
            case SIBLING:
                creature.getSiblings().add(getMythology().getCreature(value));
                return this;
            case CONSORTS:
                System.out.println("Consort " + value + getMythology().getCreature(value));
                creature.getConsorts().add(getMythology().getCreature(value));
                return this;
            case CHILD:
                System.out.println("Child " + value + getMythology().getCreature(value));
                creature.getChildren().add(getMythology().getCreature(value));
                return this;
            case KILL:
                System.out.println("Kill " + value);
                return this;
            case KILLED_BY:
                System.out.println("Killed by " + value);
                return this;
            case ATTRIBUTE:
                creature.getAttributes().add(getMythology().getAttribute(value));
                System.out.println("Attribute " + value);
                return this;
            default:
                System.out.println("No such field " + name + ", " + creature);
                return null;
        }
    }

    @Override
    public void done() {
        System.out.println(creature.getName() + " consorts " + creature.getConsorts());
    }
}

class GroupLineProcessor extends MythologyLineProcessor {
    static enum Field {
        LEADER, MEMBER
    }

    private EntityGroup entityGroup;
    private ViewableGroup group;

    public GroupLineProcessor(Mythology mythology, String name, ViewableGroup rootGroup) {
        super(mythology);
        this.entityGroup = mythology.getGroup(name);
        this.group = rootGroup.getOrCreateChild(name);
        System.out.println("Added " + name + " to root " + rootGroup);
    }

    public LineProcessor process(String name, String value) {
        Field field = getEnumValue(name, Field.values());
        if (field == null) {
            System.out.println("No such field " + name + ", " + this);
            return null;
        }
        switch (field) {
            case LEADER:
                entityGroup.setLeader(getMythology().getCreature(value));
                System.out.println("Leader " + value);
                return this;
            case MEMBER:
                Creature creat = getMythology().getCreature(value);
                entityGroup.getMembers().add(creat);
                group.addMember(creat);
                System.out.println("Member " + value);
                return this;
            default:
                System.out.println("No such field " + name + ", " + entityGroup);
                return null;
             }
    }

    public String toString() {
        return "Group " + entityGroup.getName();
    }
}

class DeedLineProcessor extends MythologyLineProcessor {
    static enum Field {
        OBJECT, SUBJECT
    }

    private String name;
    private Creature object = null;
    private Creature subject = null;

    public DeedLineProcessor(Mythology mythology, String name) {
        super(mythology);
        this.name = name;
    }

    public LineProcessor process(String name, String value) {
        Field field = getEnumValue(name, Field.values());
        if (field == null) {
            System.out.println("No such field " + name + ", " + this);
            return null;
        }
        switch (field) {
            case OBJECT:
                object = getMythology().getCreature(value);
                return this;
            case SUBJECT:
                subject = getMythology().getCreature(value);
                return this;
            default:
                System.out.println("No such field " + value + " for Deed " + name);
                return null;
        }
    }

    public void done() {
        if (object == null || subject == null) {
            System.out.println("Object or subject not given for Deed " + name);
        } else {
            Deed deed = new Deed(name, subject, object);
            subject.getDeeds().add(deed);
            System.out.println("Deed " + name + ", " + subject.getName() + "->" + object.getName());
            getMythology().addDeed(deed);
        }
    }
}
