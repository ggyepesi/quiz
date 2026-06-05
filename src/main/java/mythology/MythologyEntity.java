package mythology;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import quiz.Quizable;

public interface MythologyEntity extends Quizable {
    public Component getComponent() throws Exception;
}

class Relation extends NamedEntity {
    // Name of the relation
    // getComponent returns the full content of the relation, object action subject for Deed for example
    // arguments should tell which one should be included (like 'deeds of theseus')
    public Relation(String name) {
        super(name);
    }
}

class EntityGroup extends NamedEntity {
    private Creature leader;
    private List<Creature> members = new ArrayList<>();

    public Creature getLeader() {
        return leader;
    }

    public void setLeader(Creature leader) {
        this.leader = leader;
    }

    public List<Creature> getMembers() {
        return members;
    }

    public EntityGroup(String name) {
        super(name);
    }
}

class Death extends NamedEntity {
    public Death(String name) {
        super(name);
    }
}
