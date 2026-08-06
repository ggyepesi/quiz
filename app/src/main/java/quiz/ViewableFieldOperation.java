package quiz;

import objectview.field.FieldPath;

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

    private final FieldPath path;
    private Kind kind = Kind.EXISTS;
    private String argument;

    public ViewableFieldOperation(FieldPath path, Kind kind, String argument) {
        this.path = path == null ? FieldPath.ROOT : path;
        this.kind = kind == null ? Kind.EXISTS : kind;
        this.argument = argument;
    }

    public FieldPath getPath() {
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
        return path.dotted()
                + " "
                + kind
                + (argument == null ? "" : " " + argument);
    }
}
