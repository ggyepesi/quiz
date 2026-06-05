package misc;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Smullyan {
    public static void main(String[] args) {
        
    }
    
}

class Element {}

class Relation {
    private int arity;

    public Relation(int arity) {
        this.arity = arity;
    }

    public int getArity() {
        return arity;
    }

    public void setArity(int arity) {
        this.arity = arity;
    }
}

// A model is a set of elements and relations on them.
class Model {
    List<Element> elements;
    // relations on elements
    Map<Relation, Set<List<Element>>> relations;

    public boolean isRelation(Relation relation, List<Element> tuple) {
        Set<List<Element>> tuples = relations.get(relation);
        return tuples != null && tuples.contains(tuple);
    }
    // exists arity statement
    // forall arity tuples statement

}

interface Statement {
    public List<String> freeVariables();
    public boolean evaluate(Model model, List<Element> elements);
}

class SimpleStatement implements Statement {
    private Relation relation;
    private List<String> freeVariables;

    public SimpleStatement(Relation relation, List<String> freeVariables) {
        if (relation.getArity() != freeVariables.size()) throw new IllegalArgumentException();
        this.relation = relation;
        this.freeVariables = freeVariables;
    }

    @Override
    public boolean evaluate(Model model, List<Element> tuple) {
        // Check if any two variables are the same then the corresponding elements in the tuple are also the same.
        for (int i =0; i < freeVariables.size(); ++i) {
            for (int j = i + 1; j < freeVariables.size(); ++j) {
                if (freeVariables.get(i) == freeVariables.get(j) && tuple.get(i) != tuple.get(j)) {
                    throw new IllegalArgumentException(
                        "variables at " + i + " and " + j + " are the same but the differ in the tuple");
                }
            }
        }
        return model.isRelation(relation, tuple);
    }

    @Override
    public List<String> freeVariables() {
        return freeVariables;
    }
}

abstract class SingleStatement implements Statement {
    private Statement statement;

    public SingleStatement(Statement statement) {
        this.statement = statement;
    }
    public Statement getStatement() {
        return statement;
    }
    public void setStatement(Statement statement) {
        this.statement = statement;
    }
    
    @Override
    public List<String> freeVariables() {
        return statement.freeVariables();
    }
}

class NotStatement extends SingleStatement {
    public NotStatement(Statement statement) {
        super(statement);
    }

    @Override
    public boolean evaluate(Model model, List<Element> tuple) {
        return !getStatement().evaluate(model, tuple);
    }

}

class OrStatement implements Statement {
    private List<Statement> statements;

    public OrStatement(List<Statement> statements) {
        this.statements = statements;
    }

    public List<Statement> getStatements() {
        return statements;
    }

    public void setStatements(List<Statement> statements) {
        this.statements = statements;
    }

    @Override
    public boolean evaluate(Model model, List<Element> tuple) {
        for (Statement statement : statements) {
            if (statement.evaluate(model, tuple)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> freeVariables() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'freeVariables'");
    }
}

class AndStatement implements Statement {
    private List<Statement> statements;

    public AndStatement(List<Statement> statements) {
        this.statements = statements;
    }

    public List<Statement> getStatements() {
        return statements;
    }

    public void setStatements(List<Statement> statements) {
        this.statements = statements;
    }

    @Override
    public boolean evaluate(Model model, List<Element> tuple) {
        for (Statement statement : statements) {
            if (!statement.evaluate(model, tuple)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> freeVariables() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'freeVariables'");
    }
}
