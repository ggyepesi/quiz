package wikidata.explore.codegen;

import objectview.Viewable;
import java.util.Map;

public interface DynamicViewable extends Viewable {
    Map<String, Object> dynamicFields();
}
