package quiz;

import objectview.field.FieldSet;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/**
 * A quiz domain object. It adapts to the {@link objectview.Viewable} SPI — the sole
 * input of the objectview widgets — by bridging its {@code getX} accessors to the
 * neutral view names; the objectview library never sees {@code Quizable} itself.
 */
public interface Quizable extends objectview.Viewable {
    // getIdentifier() / getDisplayName() / getName() / typeName() are inherited from
    // objectview.Viewable (same names), so every Quizable is a Viewable with no call
    // churn. Quizable only adds the field bridge + its data-model operations.

    /** The field bridge over this object (declared reflection or a dynamic map). */
    @Override default FieldSet fields() { return FieldSet.of(this); }

    // typeName() is inherited from Viewable (dynamic objects override it with their
    // domain name; it addresses the object in the web API /api/quizable/{type}/{id}).

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
