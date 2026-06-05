package wikidata.explore.tree;

public class RuleEdge {
    private String fieldName;
    private RuleNode childNode;
    private boolean collection = true;

    public RuleEdge(String fieldName, RuleNode childNode) {
        this(fieldName, childNode, true);
    }

    public RuleEdge(String fieldName,
                    RuleNode childNode,
                    boolean collection) {

        this.fieldName = fieldName;
        this.childNode = childNode;
        this.collection = collection;
    }

    public String fieldName() {
        return fieldName;
    }

    public void fieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public RuleNode childNode() {
        return childNode;
    }

    public void childNode(RuleNode childNode) {
        this.childNode = childNode;
    }

    public boolean collection() {
        return collection;
    }

    public void collection(boolean collection) {
        this.collection = collection;
    }

    public boolean single() {
        return !collection;
    }

    public void single(boolean single) {
        this.collection = !single;
    }

    public String displayName() {
        String name =
                fieldName == null || fieldName.isBlank()
                        ? "<unnamed relation>"
                        : fieldName;

        return collection
                ? name + " [*]"
                : name + " [1]";
    }

    @Override
    public String toString() {
        return displayName();
    }
}
