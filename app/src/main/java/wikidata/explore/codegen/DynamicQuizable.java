package wikidata.explore.codegen;

import quiz.Quizable;
import java.util.Map;

public interface DynamicQuizable extends Quizable {
    Map<String, Object> dynamicFields();
}
