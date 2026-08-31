package wikidata.explore.model;

/** Finds one exact immutable module version by its logical coordinate. */
@FunctionalInterface
public interface ModelModuleResolver {
    ModelModule resolve(String moduleId, String version) throws Exception;
}
