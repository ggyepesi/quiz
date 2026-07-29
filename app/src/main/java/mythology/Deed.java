package mythology;

import objectview.ViewableAdapter;

public class Deed extends NamedEntity  {
    private NamedEntity subject;
    private NamedEntity object;

    protected Deed() {}
    
    public Deed(String name, NamedEntity subject, NamedEntity object) {
        super(name + " " + object.getName());
        this.object = object;
        this.subject = subject;
    }

    public NamedEntity getObject() {
        return object;
    }

    public NamedEntity getSubject() {
        return subject;
    }

}
