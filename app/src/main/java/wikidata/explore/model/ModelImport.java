package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** A live reference to selected classes owned by another named model. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ModelImport {
    private String modelName = "";
    private final List<String> classNames = new ArrayList<>();

    public ModelImport() { }
    public ModelImport(String modelName, List<String> classNames) {
        modelName(modelName);
        classNames(classNames);
    }

    public String modelName() { return clean(modelName); }
    public void modelName(String value) { modelName = clean(value); }
    public List<String> classNames() { return List.copyOf(classNames); }
    public void classNames(List<String> values) {
        classNames.clear();
        if (values != null) values.stream().map(ModelImport::clean)
                .filter(value -> !value.isBlank()).distinct().forEach(classNames::add);
    }
    public boolean complete() { return !modelName().isBlank() && !classNames.isEmpty(); }
    public ModelImport copy() { return new ModelImport(modelName(), classNames()); }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
