package quiz.transform.ui;

import aux.FlexibleDate;
import datasource.schema.FieldType;
import objectview.Viewable;
import objectview.field.FieldAccess;
import quiz.transform.RelationClosureGroup;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.time.LocalDate;
import java.time.Year;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The temporal projection of the same retained bridge instances used by a closure path. */
final class RelationClosureTimeline {
    // These are source-schema identities, not field-name guesses. A project may call the
    // fields anything; their configured qualifier mappings state the temporal roles.
    private static final String START_TIME = "P580";
    private static final String END_TIME = "P582";

    record DateFields(String startField, String endField) {
        DateFields {
            startField = clean(startField);
            endField = clean(endField);
        }

        boolean configured() {
            return !startField.isBlank() || !endField.isBlank();
        }

        String description() {
            if (startField.isBlank()) return "end: " + endField;
            if (endField.isBlank()) return "start: " + startField;
            return startField + " → " + endField;
        }
    }

    record Holding(Viewable relation, Viewable position, Viewable member,
                   FlexibleDate start, FlexibleDate end, int pathEdge) {
        boolean invalidInterval() {
            return start != null && end != null && start.compareTo(end) > 0;
        }

        boolean placed() {
            return !invalidInterval() && (start != null || end != null);
        }

        String dateLabel() {
            if (invalidInterval()) {
                return start.format() + " → " + end.format() + " (end precedes start)";
            }
            if (start == null && end == null) return "undated";
            if (start == null) return "? → " + end.format();
            if (end == null) return start.format() + " → ?";
            return start.format() + " → " + end.format();
        }
    }

    record Lane(Viewable position, List<Holding> holdings) {
        Lane {
            holdings = holdings == null ? List.of() : List.copyOf(holdings);
        }
    }

    record Model(List<Lane> lanes, List<Holding> unplaced,
                 double minimum, double maximum, DateFields fields) {
        Model {
            lanes = lanes == null ? List.of() : List.copyOf(lanes);
            unplaced = unplaced == null ? List.of() : List.copyOf(unplaced);
        }

        List<Holding> holdings() {
            return lanes.stream().flatMap(lane -> lane.holdings().stream()).toList();
        }

        boolean hasDates() {
            return holdings().stream().anyMatch(Holding::placed);
        }
    }

    private RelationClosureTimeline() { }

    /** Reads the temporal roles from the bridge class's configured Wikidata qualifiers. */
    static DateFields configuredFields(TransformController controller, String bridgeType) {
        ProjectBacking backing = controller == null ? null
                : controller.domain().capability(ProjectBacking.class);
        return configuredFields(backing == null ? null : backing.projectModel(), bridgeType);
    }

    static DateFields configuredFields(GeneratedProjectModel project, String bridgeType) {
        if (project == null || bridgeType == null) return new DateFields("", "");
        GeneratedClassModel bridge = project.findClass(bridgeType);
        if (bridge == null) return new DateFields("", "");
        String start = "";
        String end = "";
        for (GeneratedFieldModel field : bridge.effectiveFields(project)) {
            if (field == null || field.type() != FieldType.DATE) continue;
            String qualifier = clean(field.mapping().qualifierPid());
            if (START_TIME.equals(qualifier)) start = field.name();
            if (END_TIME.equals(qualifier)) end = field.name();
        }
        return new DateFields(start, end);
    }

    static Model of(RelationClosureGroup.RelationPath path, DateFields fields) {
        DateFields configured = fields == null ? new DateFields("", "") : fields;
        if (path == null || path.isEmpty()) {
            return new Model(List.of(), List.of(), 0, 1, configured);
        }
        Map<String, Viewable> positions = new LinkedHashMap<>();
        for (RelationClosureGroup.PathNode node : path.nodes()) {
            if (node.role() == RelationClosureGroup.PathRole.ENTITY) {
                positions.putIfAbsent(identity(node.instance()), node.instance());
            }
        }
        Map<String, List<Holding>> byPosition = new LinkedHashMap<>();
        positions.keySet().forEach(key -> byPosition.put(key, new ArrayList<>()));
        List<Holding> unplaced = new ArrayList<>();
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < path.edges().size(); index++) {
            RelationClosureGroup.PathEdge edge = path.edges().get(index);
            RelationClosureGroup.PathNode entity = edge.source().role()
                    == RelationClosureGroup.PathRole.ENTITY ? edge.source() : edge.target();
            RelationClosureGroup.PathNode member = edge.source().role()
                    == RelationClosureGroup.PathRole.MEMBER ? edge.source() : edge.target();
            FlexibleDate start = date(edge.bridge(), configured.startField());
            FlexibleDate end = date(edge.bridge(), configured.endField());
            Holding holding = new Holding(edge.bridge(), entity.instance(), member.instance(),
                    start, end, index + 1);
            byPosition.computeIfAbsent(identity(entity.instance()), ignored -> new ArrayList<>())
                    .add(holding);
            if (!holding.placed()) {
                unplaced.add(holding);
            } else {
                if (start != null) {
                    minimum = Math.min(minimum, coordinate(start));
                    maximum = Math.max(maximum, coordinate(start));
                }
                if (end != null) {
                    minimum = Math.min(minimum, coordinate(end));
                    maximum = Math.max(maximum, coordinate(end));
                }
            }
        }
        List<Lane> lanes = new ArrayList<>();
        for (Map.Entry<String, Viewable> position : positions.entrySet()) {
            lanes.add(new Lane(position.getValue(),
                    byPosition.getOrDefault(position.getKey(), List.of())));
        }
        if (!Double.isFinite(minimum)) {
            minimum = 0;
            maximum = 1;
        } else if (minimum == maximum) {
            minimum -= 1;
            maximum += 1;
        }
        return new Model(lanes, unplaced, minimum, maximum, configured);
    }

    static double coordinate(FlexibleDate date) {
        if (date == null) return Double.NaN;
        double value = date.getYear();
        if (date.getMonth() > 0) value += (date.getMonth() - 1) / 12.0;
        if (date.getDay() > 0) value += (date.getDay() - 1) / 366.0;
        return value;
    }

    private static FlexibleDate date(Viewable relation, String field) {
        if (relation == null || field == null || field.isBlank()) return null;
        return date(FieldAccess.getPath(relation, field));
    }

    private static FlexibleDate date(Object value) {
        if (value instanceof Collection<?> values) {
            for (Object item : values) {
                FlexibleDate found = date(item);
                if (found != null) return found;
            }
            return null;
        }
        if (value instanceof FlexibleDate date) return date;
        if (value instanceof LocalDate date) {
            return new FlexibleDate(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        }
        if (value instanceof YearMonth date) {
            return new FlexibleDate(date.getYear(), date.getMonthValue());
        }
        if (value instanceof Year year) return new FlexibleDate(year.getValue());
        return value == null ? null : FlexibleDate.parse(value.toString());
    }

    private static String identity(Viewable value) {
        if (value == null) return "";
        return clean(value.identityTypeName()) + "\u001f" + clean(value.getIdentifier());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
