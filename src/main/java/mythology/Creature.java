package mythology;

import java.util.ArrayList;
import java.util.List;

import quiz.QuizableAdapter;

// Creature is living
public class Creature extends NamedEntity {
    private Creature father;
    private Creature mother;
    private List<Creature> siblings = new ArrayList<>();
    private List<Creature> children = new ArrayList<>();
    private List<Creature> consorts = new ArrayList<>();
    private List<EntityGroup> groups = new ArrayList<>();
    private List<Deed> deeds = new ArrayList<>();
    private List<Attribute> attributes = new ArrayList<>();
    
    protected Creature() {}

    public Creature(String name) {
        super(name);
    }

    public List<Creature> getSiblings() {
        return siblings;
    }

    public Creature getFather() {
        return father;
    }

    public Creature getMother() {
        return mother;
    }

    public List<Creature> getChildren() {
        return children;
    }

    public List<Creature> getConsorts() {
        return consorts;
    }

    public List<EntityGroup> getGroups() {
        return groups;
    }

    public List<Deed> getDeeds() {
        return deeds;
    }

    public List<Attribute> getAttributes() {
        return attributes;
    }
    
    public void setFather(Creature father) {
        this.father = father;
    }

    public void setMother(Creature mother) {
        this.mother = mother;
    }

    @Override
    public QuizableAdapter createNew() {
        return new Creature();
    }
}
