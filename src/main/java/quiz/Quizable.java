package quiz;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;

public interface Quizable {    
    public String getName();
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
