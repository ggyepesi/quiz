package domain.config;

import domain.datasource.api.SourceBinding;
import domain.datasource.api.SourceBindingSlot;
import domain.datasource.api.SourceBindingTarget;
import domain.datasource.api.SourceRecipe;
import domain.datasource.schema.FieldType;
import domain.datasource.wikipedia.WikipediaDatasourceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A text source is a project declaration: it persists with its project, is named by its
 * declaration id in a story recipe, travels with an imported class whose recipe reads it,
 * and a project is offered only the text sources it declares or imports.
 */
class TextSourceTest {

    private static final String BRODEUR = "The Prose Edda (1916 translation by Arthur Gilchrist Brodeur)/";

    private static GeneratedProjectModel library(TextSource text) {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("NorseTexts");
        model.projectKind(GeneratedProjectModel.ProjectKind.MODEL);
        GeneratedClassModel character = new GeneratedClassModel("NorseCharacter");
        model.addClass(character);
        GeneratedClassModel work = new GeneratedClassModel("NorseLiteraryWork");
        model.addClass(work);
        GeneratedClassModel story = new GeneratedClassModel("NorseStory");
        story.classKind(ClassKind.SOURCE);
        model.addClass(story);
        story.addField("title", FieldType.TEXT, FieldCardinality.SINGLE);
        story.addField("text", FieldType.TEXT, FieldCardinality.SINGLE);
        story.addField("work", FieldType.ENTITY, FieldCardinality.SINGLE).entityClassName("NorseLiteraryWork");
        story.addField("characters", FieldType.ENTITY, FieldCardinality.COLLECTION)
                .entityClassName("NorseCharacter");
        GeneratedFieldModel stories = work.addField("stories", FieldType.ENTITY, FieldCardinality.COLLECTION);
        stories.entityClassName("NorseStory");
        if (text != null) model.addTextSource(text);
        FieldSourceBindings.put(stories, recipe(text == null ? "decl:missing" : text.declarationId()));
        return model;
    }

    private static SourceBinding recipe(String textSourceId) {
        return new SourceBinding(SourceBindingTarget.fieldValue("NorseLiteraryWork", "stories",
                SourceBindingSlot.CANDIDATE_EVIDENCE),
                new SourceRecipe(WikipediaDatasourceProvider.ID,
                        WikipediaDatasourceProvider.TEXT_PASSAGE_INSTANCES, Map.of(
                                WikipediaDatasourceProvider.TEXT_SOURCES, textSourceId,
                                WikipediaDatasourceProvider.PASSAGE_TEXT_FIELD, "text",
                                WikipediaDatasourceProvider.PASSAGE_SOURCE_FIELD, "work",
                                WikipediaDatasourceProvider.PASSAGE_TITLE_FIELD, "title",
                                WikipediaDatasourceProvider.PASSAGE_REFERENCES_FIELD, "characters")));
    }

    private static TextSource proseEdda() {
        TextSource text = new TextSource("Prose Edda (Brodeur, 1916)");
        text.workQid("q205882");
        text.pages(List.of(BRODEUR + "Gylfaginning", BRODEUR + "Skáldskaparmál"));
        return text;
    }

    @Test void aTextSourcePersistsWithItsProjectAndItsRecipeNamesItById(@TempDir Path dir) throws Exception {
        TextSource text = proseEdda();
        File file = dir.resolve("norse.model.json").toFile();
        new GeneratedProjectModelStore().save(library(text), file);

        GeneratedProjectModel loaded = new GeneratedProjectModelStore().load(file);
        TextSource saved = loaded.findTextSourceById(text.declarationId());
        assertNotNull(saved);
        assertEquals("Q205882", saved.workQid());
        assertEquals(List.of(BRODEUR + "Gylfaginning", BRODEUR + "Skáldskaparmál"), saved.pages());
        assertTrue(GeneratedProjectModelValidator.validate(loaded).valid(),
                GeneratedProjectModelValidator.validate(loaded).format());
    }

    @Test void aProjectWithoutTextSourcesWritesNoTextSourceEntry(@TempDir Path dir) throws Exception {
        File file = dir.resolve("plain.model.json").toFile();
        new GeneratedProjectModelStore().save(new GeneratedProjectModel(), file);

        assertFalse(Files.readString(file.toPath()).contains("textSources"),
                "existing models and their signatures stay unchanged");
    }

    @Test void aRecipeNamingATextSourceThisProjectDoesNotHaveIsInvalid() {
        var problems = GeneratedProjectModelValidator.validate(library(null)).format();

        assertTrue(problems.contains("Text source decl:missing is not declared or imported by this project"),
                problems);
    }

    @Test void aTextSourceNamesItsWorkAndAPage() {
        TextSource incomplete = new TextSource("Draft");
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.addTextSource(incomplete);

        String problems = GeneratedProjectModelValidator.validate(model).format();
        assertTrue(problems.contains("names the Wikidata item of its work"), problems);
        assertTrue(problems.contains("names at least one page"), problems);
    }

    @Test void theRecipeReferencesItsTextSourceInTheInventory() {
        TextSource text = proseEdda();
        var references = ConfigurationReferenceInventory.of(library(text)).references().stream()
                .filter(reference -> reference.relation()
                        == ConfigurationReferenceInventory.Relation.DATASOURCE_RECIPE_TEXT_SOURCE)
                .toList();

        assertEquals(1, references.size());
        assertEquals(text.declarationId(), references.getFirst().target().declarationId());
        assertEquals("Prose Edda (Brodeur, 1916)", references.getFirst().target().name());
    }

    @Test void importingTheWorkBringsItsTextSourceOwnedByItsModelAndSaveLeavesItThere() {
        TextSource text = proseEdda();
        GeneratedProjectModel owner = library(text);
        GeneratedProjectModel greek = new GeneratedProjectModel();
        greek.name("GreekMythology");
        assertTrue(greek.textSources().isEmpty(), "a project is offered no other domain's texts");

        GeneratedProjectModel norse = new GeneratedProjectModel();
        norse.name("NorseMythology");
        ClassImportPlan.of(owner, norse, "NorseLiteraryWork")
                .apply(Set.of(), ClassImportPlan.Ownership.IMPORT);

        TextSource imported = norse.findTextSourceById(text.declarationId());
        assertNotNull(imported, "the class's recipe still names a text source this project has");
        assertEquals("NorseTexts", imported.importedFrom());
        assertNull(norse.withoutResolvedImports().findTextSourceById(text.declarationId()),
                "an imported text source is saved by the model that owns it");
    }

    @Test void copyingAClassOnlyBringsTextSourcesOfTheSelectedClasses() {
        TextSource remoteText = proseEdda();
        GeneratedProjectModel source = library(remoteText);
        var workIndex = new GeneratedClassModel("WorkIndex");
        workIndex.addField("work", FieldType.ENTITY, FieldCardinality.SINGLE)
                .entityClassName("NorseLiteraryWork");
        source.addClass(workIndex);
        TextSource localText = proseEdda();
        localText.pages(List.of("Local edition"));
        GeneratedProjectModel target = library(localText);
        target.name("LocalModel");

        var plan = ClassImportPlan.of(source, target, "WorkIndex");
        assertTrue(plan.conflicts(ClassImportPlan.Ownership.COPY, Set.of()).isEmpty());
        assertTrue(plan.conflicts(ClassImportPlan.Ownership.COPY, Set.of("NorseLiteraryWork"))
                .contains(localText.name()));
        plan.apply(Set.of(), ClassImportPlan.Ownership.COPY);

        assertNotNull(target.findClass("WorkIndex"));
        assertEquals(List.of("Local edition"),
                target.findTextSourceById(localText.declarationId()).pages());
        assertNull(target.findTextSourceById(remoteText.declarationId()));

        var emptyTarget = new GeneratedProjectModel();
        var fullCopy = ClassImportPlan.of(source, emptyTarget, "WorkIndex");
        fullCopy.apply(fullCopy.dependencyClassNames(), ClassImportPlan.Ownership.COPY);
        assertEquals(remoteText.pages(),
                emptyTarget.findTextSourceById(remoteText.declarationId()).pages());
        assertFalse(emptyTarget.findTextSourceById(remoteText.declarationId()).isImported());
    }
}
