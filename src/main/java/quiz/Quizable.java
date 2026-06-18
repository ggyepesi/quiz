package quiz;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;

public interface Quizable {
    /** Stable unique key used for map keys, cartesian product keys, and identity checks. */
    String getIdentifier();

    /** Human-readable label shown in UI, titles, and logging. */
    String getDisplayName();

    /** Backward-compatible bridge — delegates to getDisplayName(). */
    default String getName() { return getDisplayName(); }

    /**
     * The dataset/type name used to address this object in the web API
     * ({@code /api/quizable/{type}/{id}}) and to key it in the store. Defaults
     * to the class's simple name (so hand-written model classes are addressed
     * by their class), but generated/dynamic objects — all of one Java class —
     * override it with their domain name (e.g. "Constellation").
     */
    default String typeName() { return getClass().getSimpleName(); }

    public boolean hasField(String fieldName);
    public boolean hasAnyField();
    public boolean hasFields(Collection<String> fieldNames);
 
    // generate Quizable instances having single values on the specifed fieldNames.
    // The key in the returned map is the list of single values on the specified fields.
    public HashMap<List<Object>, Quizable> generateUniqueCombinations(List<String> fieldNames);

    // Create and return Quizable with the specifed values on the specified fields
    public Quizable project(List<String> fieldNames, List<Object> fieldValues);

    //fieldName -> name to show
    //public Map<String, String> getFieldNames();
}
