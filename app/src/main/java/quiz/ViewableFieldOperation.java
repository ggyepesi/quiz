package quiz;

import java.util.ArrayList;
import java.util.List;

public class ViewableFieldOperation {
    public enum Kind {
        EXISTS,
        EMPTY,
        CONTAINS,
        EQUALS,
        NOT_EQUALS,
        GREATER_THAN,
        LESS_THAN,
        GREATER_OR_EQUAL,
        LESS_OR_EQUAL
    }

    private final List<String> path = new ArrayList<>();
    private Kind kind = Kind.EXISTS;
    private String argument;

    public ViewableFieldOperation(List<String> path, Kind kind, String argument) {
        if (path != null) {
            this.path.addAll(path);
        }

        this.kind = kind == null ? Kind.EXISTS : kind;
        this.argument = argument;
    }

    public List<String> getPath() {
        return path;
    }

    public Kind getKind() {
        return kind;
    }

    public String getArgument() {
        return argument;
    }

    @Override
    public String toString() {
        return String.join(".", path)
                + " "
                + kind
                + (argument == null ? "" : " " + argument);
    }
}