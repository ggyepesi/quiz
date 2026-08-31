package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** An exact, reproducible dependency on one immutable model-module version. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ModelModuleImport {
    private String moduleId = "";
    private String version = "";
    private String contentDigest = "";
    private final List<String> declarationIds = new ArrayList<>();

    public ModelModuleImport() { }

    public ModelModuleImport(String moduleId, String version, String contentDigest,
            List<String> declarationIds) {
        moduleId(moduleId);
        version(version);
        contentDigest(contentDigest);
        declarationIds(declarationIds);
    }

    public String moduleId() { return clean(moduleId); }
    public void moduleId(String value) { moduleId = clean(value); }
    public String version() { return clean(version); }
    public void version(String value) { version = clean(value); }
    public String contentDigest() { return clean(contentDigest); }
    public void contentDigest(String value) { contentDigest = clean(value); }
    public List<String> declarationIds() { return List.copyOf(declarationIds); }
    public void declarationIds(List<String> values) {
        declarationIds.clear();
        if (values != null) values.stream().map(DeclarationIds::clean)
                .filter(value -> !value.isBlank()).distinct().forEach(declarationIds::add);
    }

    public String coordinate() { return moduleId() + "@" + version(); }

    public boolean complete() {
        return !moduleId().isBlank() && !version().isBlank()
                && !contentDigest().isBlank() && !declarationIds.isEmpty();
    }

    public ModelModuleImport copy() {
        return new ModelModuleImport(moduleId(), version(), contentDigest(), declarationIds);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
