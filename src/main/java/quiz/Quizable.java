package quiz;

import objectview.Viewable;
import objectview.field.FieldSet;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/**
 * A quiz domain object. It adapts to the {@link Viewable} SPI — the sole input
 * of the objectview widgets — by bridging its {@code getX} accessors to the
 * neutral view names; the objectview library never sees {@code Quizable}
 * itself.
 */
public interface Quizable extends Viewable {

    // getIdentifier() / getDisplayName() / getName() / typeName() are inherited
    // from objectview.Viewable (same names), so every Quizable is a Viewable
    // with no call churn. Quizable only adds the field bridge + its data-model
    // operations.

    // typeName() is inherited from Viewable (dynamic objects override it with
    // their domain name; it addresses the object in the web API
    // /api/quizable/{type}/{id}).

    /** The field bridge over this object. A default so a hand-written Quizable that
     *  doesn't extend a reflection base (e.g. QuizableAdapter/ViewableAdapter, which
     *  supply their own) still satisfies the Viewable SPI. */
    @Override
    default FieldSet fields() {
        return FieldSet.of(this);
    }

    boolean hasField(String fieldName);

    boolean hasAnyField();

    boolean hasFields(Collection<String> fieldNames);

    // generate Quizable instances having single values on the specified
    // fieldNames. The key in the returned map is the list of single values on
    // the specified fields.
    HashMap<List<Object>, Quizable> generateUniqueCombinations(
            List<String> fieldNames);

    // Create and return Quizable with the specified values on the specified
    // fields.
    Quizable project(
            List<String> fieldNames,
            List<Object> fieldValues);

    // fieldName -> name to show
    // public Map<String, String> getFieldNames();
}
