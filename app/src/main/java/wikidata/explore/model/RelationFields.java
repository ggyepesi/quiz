package wikidata.explore.model;

import datasource.schema.FieldType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The relations a class states about its own members.
 *
 * <p>A relation over a class is an entity field of that class pointing back at it —
 * {@code Position.replaces}, {@code Position.superClasses}. Two such fields are ONE
 * relation seen from both ends when Wikidata says so through P1696, which is why the
 * pairing is proposed from the cached property metadata and not from the field names:
 * {@code replaces}/{@code replacedBy} pair, but so do {@code follows}/{@code followedBy}
 * and any future pair whose names rhyme with nothing.
 *
 * <p>A property with no stated converse is profilable alone; P279 has no inverse property
 * and is no less a relation for it. What is refused is guessing a pair.
 */
public final class RelationFields {
    private RelationFields() { }

    /** One relation of a class, stated by one field or by a converse pair. */
    public record Relation(
            String className,
            String forwardField, String forwardPid,
            String inverseField, String inversePid) {

        public Relation {
            forwardField = clean(forwardField);
            forwardPid = clean(forwardPid).toUpperCase(Locale.ROOT);
            inverseField = clean(inverseField);
            inversePid = clean(inversePid).toUpperCase(Locale.ROOT);
        }

        public boolean paired() { return !inverseField.isBlank(); }

        /** How the relation reads in a report: "replaces ⇄ replacedBy", or "superClasses". */
        public String label() {
            return paired() ? forwardField + " ⇄ " + inverseField : forwardField;
        }
    }

    /**
     * The relations declared by {@code clazz}, pairing two fields when
     * {@code inverseByPid} says their properties are converses.
     *
     * @param inverseByPid PID to its stated inverse PIDs, from the cached property
     *                     catalogue. An empty map proposes every field on its own,
     *                     which is what an un-refreshed cache should do — offer the
     *                     single-property reading rather than invent a pairing.
     */
    public static List<Relation> of(GeneratedClassModel clazz, GeneratedProjectModel model,
                                    Map<String, Set<String>> inverseByPid) {
        if (clazz == null) return List.of();
        List<GeneratedFieldModel> selfReferencing = new ArrayList<>();
        for (GeneratedFieldModel field : model == null
                ? clazz.fields() : clazz.effectiveFields(model)) {
            if (field == null || field.type() != FieldType.ENTITY) continue;
            String target = field.entityClassName();
            if (target == null || target.isBlank()) continue;
            if (model == null
                    ? target.equals(clazz.className())
                    : model.isSameOrSubclass(target, clazz.className())
                            || model.isSameOrSubclass(clazz.className(), target)) {
                selfReferencing.add(field);
            }
        }

        List<Relation> relations = new ArrayList<>();
        Set<String> taken = new LinkedHashSet<>();
        for (GeneratedFieldModel field : selfReferencing) {
            if (!taken.add(field.name())) continue;
            String pid = pid(field);
            GeneratedFieldModel converse = null;
            if (!pid.isBlank() && inverseByPid != null) {
                Set<String> inverses = inverseByPid.getOrDefault(pid, Set.of());
                for (GeneratedFieldModel candidate : selfReferencing) {
                    if (candidate == field || taken.contains(candidate.name())) continue;
                    String candidatePid = pid(candidate);
                    if (!candidatePid.isBlank() && inverses.contains(candidatePid)) {
                        converse = candidate;
                        break;
                    }
                }
            }
            if (converse == null) {
                relations.add(new Relation(clazz.className(), field.name(), pid, "", ""));
            } else {
                taken.add(converse.name());
                relations.add(new Relation(clazz.className(),
                        field.name(), pid, converse.name(), pid(converse)));
            }
        }
        return List.copyOf(relations);
    }

    private static String pid(GeneratedFieldModel field) {
        FieldSourceMapping mapping = field.mapping();
        return mapping == null ? "" : clean(mapping.propertyPid()).toUpperCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
