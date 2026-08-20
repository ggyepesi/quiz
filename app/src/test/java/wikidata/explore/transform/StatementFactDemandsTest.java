package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.compiled.ProjectModelCompiler;
import wikidata.explore.generation.GenerationFactDemandPlan;
import wikidata.explore.generation.GenerateDomainPipeline;
import wikidata.explore.model.GeneratedProjectModelStore;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementFactDemandsTest {

    @Test
    void oscarsRoutesRoleAndKindClosuresBackToTheStatementSubject() throws Exception {
        var model = new GeneratedProjectModelStore().load(new File(
                "../data/wikidata/oscarnominations/oscarnominations.model.json"));
        var compiled = ProjectModelCompiler.compile(model);
        var recipe = ModelStatementReifications.derive(compiled).getFirst();

        StatementFactDemands routes = StatementFactDemands.compile(
                compiled, recipe, GenerationFactDemandPlan.compile(model));

        Set<String> subjectPids = new LinkedHashSet<>();
        routes.subjectDemands().forEach(d -> subjectPids.addAll(d.propertyPids()));
        assertTrue(subjectPids.containsAll(Set.of(
                "P31", "P136", "P569", "P734", "P735",
                "P1477", "P1559", "P742")), subjectPids.toString());

        Set<String> forWork = new LinkedHashSet<>();
        routes.forField("forWork").forEach(d -> forWork.addAll(d.propertyPids()));
        assertTrue(forWork.containsAll(Set.of("P31", "P136")), forWork.toString());

        Set<String> nominee = new LinkedHashSet<>();
        routes.forField("nominee").forEach(d -> nominee.addAll(d.propertyPids()));
        assertTrue(nominee.containsAll(Set.of("P31", "P569", "P734", "P735")),
                nominee.toString());

        String acquisitionDetails = String.join("\n", GenerateDomainPipeline
                .configured(model).snapshot().stream()
                .filter(state -> state.phase().id().equals(
                        GenerateDomainPipeline.ACQUIRE_STATEMENTS))
                .findFirst().orElseThrow().phase().details());
        assertTrue(acquisitionDetails.contains("subject response — retain role closure"),
                acquisitionDetails);
        assertTrue(acquisitionDetails.contains("P136")
                && acquisitionDetails.contains("P569"), acquisitionDetails);
    }
}
