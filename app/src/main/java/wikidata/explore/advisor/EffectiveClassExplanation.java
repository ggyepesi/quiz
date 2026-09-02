package wikidata.explore.advisor;

import java.util.List;
import java.util.Optional;

/** Read-only explanation of the class shape generation actually sees. */
public record EffectiveClassExplanation(
        String className,
        String declaration,
        String instances,
        List<Field> fields,
        /** What makes one record distinct from another, and what happens when two
         *  still collide. Blank when the class declares no canonical key. */
        String identity,
        /** Empty when nothing has looked: absent evidence is not evidence of absence.
         *  A present empty list means the reverse index ran and found no reference,
         *  which is a finding; the two must not render alike. */
        Optional<List<String>> uses,
        String unavailableReason) {

    /**
     * Which of three different jobs a field does. A reified statement is one fact with
     * things said about it, and a flat list of six peers hides that: two of the fields
     * ARE the statement, some of the rest tell one statement from another that looks
     * the same, and the remainder merely describe it.
     */
    public enum Part {
        /** The statement's subject — the entity the fact is about. */
        SUBJECT,
        /** The statement's own value. */
        VALUE,
        /** Said about the statement, and part of what tells two of them apart. */
        DISTINGUISHING,
        /** Said about the statement, and not part of its identity. */
        DESCRIBING,
        /** An ordinary field on a class that is not a reified statement. */
        PLAIN
    }

    public record Field(String name, String type, String origin, Part part,
                        String filledBy) {
        public Field(String name, String type, String origin) {
            this(name, type, origin, Part.PLAIN, "");
        }

        public Field(String name, String type, String origin, Part part) {
            this(name, type, origin, part, "");
        }

        public Field {
            filledBy = clean(filledBy);
            name = clean(name);
            type = clean(type);
            origin = clean(origin);
            part = part == null ? Part.PLAIN : part;
        }
    }

    public EffectiveClassExplanation {
        className = clean(className);
        declaration = clean(declaration);
        instances = clean(instances);
        fields = fields == null ? List.of() : List.copyOf(fields);
        identity = clean(identity);
        uses = uses == null ? Optional.empty() : uses.map(List::copyOf);
        unavailableReason = clean(unavailableReason);
    }

    public boolean available() { return unavailableReason.isBlank(); }

    /** Whether anything has computed which declarations refer to this class. */
    public boolean usesKnown() { return uses.isPresent(); }

    /** The fields doing one particular job, in declaration order. */
    public List<Field> fields(Part part) {
        return fields.stream().filter(field -> field.part() == part).toList();
    }

    /** Whether this class is a reified statement, and so has parts at all. */
    public boolean hasParts() {
        return fields.stream().anyMatch(field -> field.part() != Part.PLAIN);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
