package wikidata.explore.view;

import quiz.facet.Facet;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFacet;
import wikidata.explore.model.GeneratedFieldModel;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bridges a class's declared {@link GeneratedFacet}s (domain/view layer) to the
 * runtime {@link quiz.facet.Facet}s that {@code FacetGrouper} consumes — so a
 * domain can DECLARE its groupings instead of each dataset hand-building a facet
 * tree. Also suggests sensible default facets from a class's fields, mirroring how
 * {@code MembershipFields} suggests the intrinsic grouping FIELDS.
 */
public final class DomainFacets {

    private DomainFacets() {}

    /** Translate every declared facet of {@code clazz} into a runtime facet. */
    public static List<Facet> toFacets(GeneratedClassModel clazz) {
        List<Facet> out = new ArrayList<>();
        if (clazz == null) {
            return out;
        }
        for (GeneratedFacet f : clazz.facets()) {
            Facet rf = toFacet(f, clazz);
            if (rf != null) {
                out.add(rf);
            }
        }
        return out;
    }

    /** One declared facet → a runtime {@link Facet}. */
    public static Facet toFacet(GeneratedFacet spec, GeneratedClassModel clazz) {
        if (spec == null || spec.fieldName().isBlank()) {
            return null;
        }
        String field = spec.fieldName();
        String label = spec.name().isBlank() ? field : spec.name();
        return switch (spec.bucketing()) {
            case VALUE -> isEntityField(clazz, field)
                    ? Facet.reference(field, label)   // buckets carry the entity
                    : Facet.field(field, label);
            case FIRST_LETTER -> Facet.mapped(label, field, DomainFacets::firstLetter);
            case RANGE -> {
                int size = spec.rangeSize();
                yield Facet.mapped(label, field, v -> rangeBucket(v, size));
            }
        };
    }

    /**
     * Default facets proposed from a class's fields: by the membership target
     * field, by {@code type} (P31), and by decade for any date/year field. Used to
     * seed the declarations (the user then edits/removes them).
     */
    public static List<GeneratedFacet> suggestFor(GeneratedClassModel clazz) {
        List<GeneratedFacet> out = new ArrayList<>();
        if (clazz == null) {
            return out;
        }
        String relationPid = cleanPid(clazz.instanceMapping().propertyPid());
        Set<String> used = new LinkedHashSet<>();
        for (GeneratedFieldModel f : clazz.fields()) {
            String name = f.name();
            String pid = cleanPid(f.mapping().propertyPid());
            if (name == null || name.isBlank() || used.contains(name)) {
                continue;
            }
            if (pid.equals("P31")) {
                out.add(new GeneratedFacet("by type", name, GeneratedFacet.Bucketing.VALUE));
                used.add(name);
            } else if (!relationPid.isEmpty() && pid.equals(relationPid)) {
                out.add(new GeneratedFacet("by " + name, name,
                        GeneratedFacet.Bucketing.VALUE));
                used.add(name);
            } else if (f.type() == FieldType.DATE
                    || name.toLowerCase().contains("year")
                    || name.toLowerCase().contains("date")) {
                GeneratedFacet g = new GeneratedFacet("by decade", name,
                        GeneratedFacet.Bucketing.RANGE);
                g.rangeSize(10);
                out.add(g);
                used.add(name);
            }
        }
        return out;
    }

    // --- bucketing primitives (pure, unit-tested) ---

    /** First character uppercased; non-letters bucket under "#". */
    static String firstLetter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        char c = value.trim().charAt(0);
        return Character.isLetter(c) ? String.valueOf(Character.toUpperCase(c)) : "#";
    }

    /** Floor a leading number to {@code size}; size 10 reads as a decade ("2000s"). */
    static String rangeBucket(String value, int size) {
        if (value == null) {
            return null;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("-?\\d+").matcher(value.trim());
        if (!m.find()) {
            return null;
        }
        int s = size <= 0 ? 10 : size;
        long n = Long.parseLong(m.group());
        long start = Math.floorDiv(n, s) * s;
        return s == 10 ? start + "s" : start + "–" + (start + s - 1);
    }

    private static boolean isEntityField(GeneratedClassModel clazz, String fieldName) {
        for (GeneratedFieldModel f : clazz.fields()) {
            if (fieldName.equals(f.name())) {
                return f.type() == FieldType.ENTITY;
            }
        }
        return false;
    }

    private static String cleanPid(String s) {
        if (s == null) {
            return "";
        }
        s = s.trim();
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }
}
