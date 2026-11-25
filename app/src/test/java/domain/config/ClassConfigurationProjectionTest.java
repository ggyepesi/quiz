package domain.config;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The population node used to ask only whether a class had a catalogue population recipe,
 * so NorseGiant and NorseRealm (subclass by condition), every class filled by being
 * referenced, and DeityGroup (a Wikipedia category population) were offered
 * "+ Configure population source" as if nothing were set. Blank means unset, and nothing
 * else: the node reads the same membership decision generation uses.
 */
class ClassConfigurationProjectionTest {

    @Test void aConfiguredPopulationIsNeverOfferedAsAnEmptySlot() throws Exception {
        GeneratedProjectModel norse = load("norsemythology");
        assertPopulation(norse, "NorseGiant", ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Population: Subclass by condition");
        assertPopulation(norse, "NorseRealm", ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Population: Subclass by condition");
        assertPopulation(norse, "NorseLiteraryWork", ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Population: Seeded");
        assertPopulation(norse, "MythologicalEntity", ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Population: Referenced only");

        GeneratedProjectModel egypt = load("egyptiangods");
        assertPopulation(egypt, "DeityGroup", ConfigurationNode.Kind.STORED_CONFIGURATION,
                "Population: Wikipedia — Direct category members");
    }

    @Test void aClassWithNoPopulationOffersTheSlot() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel lonely = new GeneratedClassModel("Lonely");
        project.rootClass(lonely);
        assertPopulation(project, "Lonely", ConfigurationNode.Kind.AVAILABLE_SLOT,
                "+ Configure population source");
    }

    @Test void optionalSectionsWithNothingSetAreAddSlots() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel lonely = new GeneratedClassModel("Lonely");
        project.rootClass(lonely);
        ConfigurationNode root = ClassConfigurationProjection.project(
                project, lonely, ClassKind.SOURCE);
        assertEquals(ConfigurationNode.Kind.AVAILABLE_SLOT,
                section(root, ClassConfigurationProjection.REPRESENTATION).kind());
        assertEquals(ConfigurationNode.Kind.AVAILABLE_SLOT,
                section(root, ClassConfigurationProjection.SOURCE_LINKS).kind());
    }

    private static void assertPopulation(GeneratedProjectModel project, String className,
            ConfigurationNode.Kind kind, String caption) {
        GeneratedClassModel clazz = project.findClass(className);
        ConfigurationNode population = section(ClassConfigurationProjection.project(
                project, clazz, clazz.classKind()), ClassConfigurationProjection.PRODUCTION);
        assertEquals(caption, population.caption(), className);
        assertEquals(kind, population.kind(), className);
    }

    private static ConfigurationNode section(ConfigurationNode root, String detailKey) {
        return root.children().stream().filter(node -> node.detailKey().equals(detailKey))
                .findFirst().orElseThrow(() -> new AssertionError("no " + detailKey));
    }

    private static GeneratedProjectModel load(String name) throws Exception {
        return new GeneratedProjectModelStore().load(
                new File("../data/wikidata/" + name + "/" + name + ".model.json"));
    }
}
