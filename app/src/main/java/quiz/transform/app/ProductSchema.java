package quiz.transform.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The compiled schema of a domain: every declared {@link ProductClass} keyed by
 * className, plus the subset that are top-level MEMBER classes (selectable types
 * you navigate — as opposed to classes that only appear as reference targets).
 *
 * <p>This is the single typed schema the transform context reads. Non-member
 * classes are still present so a nested reference can resolve its target's
 * fields. Produced by {@code ProductCompiler} from the declared model + pool.
 */
public final class ProductSchema {

    private final Map<String, ProductClass> byName = new LinkedHashMap<>();
    private final List<String> memberClasses;

    public ProductSchema(List<ProductClass> classes, List<String> memberClasses) {
        for (ProductClass c : classes) {
            byName.put(c.className(), c);
        }
        this.memberClasses = List.copyOf(memberClasses);
    }

    /** Top-level selectable types (members), in order. */
    public List<String> memberClasses() {
        return memberClasses;
    }

    /** Every compiled class (members AND reference-target-only classes), in order. */
    public List<String> allClassNames() {
        return new ArrayList<>(byName.keySet());
    }

    public boolean isMember(String className) {
        return memberClasses.contains(className);
    }

    public ProductClass get(String className) {
        return byName.get(className);
    }

    /** The non-structural fields of a class — what the pickers/operations see. */
    public List<ProductField> fields(String className) {
        ProductClass c = byName.get(className);
        if (c == null) {
            return List.of();
        }
        List<ProductField> out = new ArrayList<>();
        for (ProductField f : c.fields()) {
            if (!f.structural()) {
                out.add(f);
            }
        }
        return out;
    }

    /** Top-level structural field names of a class (wikidata, reify source). */
    public Set<String> structuralFields(String className) {
        ProductClass c = byName.get(className);
        if (c == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (ProductField f : c.fields()) {
            if (f.structural()) {
                out.add(f.name());
            }
        }
        return out;
    }
}
