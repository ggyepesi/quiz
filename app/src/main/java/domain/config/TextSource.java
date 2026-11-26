package domain.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import domain.datasource.evidence.TextPassages;
import wikidata.WikidataIds;
import wikipedia.WikipediaArticleClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A named text of one work: the pages on a MediaWiki site that hold it, and where its
 * author divided it. A project declaration beside classes and selections, with the same
 * identity, ownership and import rules; it is not a {@link Selection}, which picks
 * entities from the pool, while a text source names a document.
 *
 * <p>A project offers only the text sources it declares or imports, so a recipe in one
 * domain is never offered another domain's texts. The division belongs to the text: the
 * Poetic Edda numbers stanzas, Brodeur's Prose Edda numbers chapters.</p>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TextSource {

    private String name = "";
    private String declarationId = "";
    private String importedFrom = "";
    private String workQid = "";
    private WikipediaArticleClient.Site site = WikipediaArticleClient.Site.WIKISOURCE;
    private final List<String> pages = new ArrayList<>();
    private TextPassages.Division division = TextPassages.Division.NUMBERED_CHAPTERS;

    public TextSource() {
    }

    public TextSource(String name) {
        declarationId = DeclarationIds.create();
        name(name);
    }

    public String name() { return name == null ? "" : name; }
    public void name(String value) { name = value == null ? "" : value.trim(); }

    public String declarationId() { return DeclarationIds.clean(declarationId); }
    void ensureDeclarationId(String projectName) {
        if (declarationId().isBlank()) {
            declarationId = DeclarationIds.legacy(projectName, "text source", name());
        }
    }

    public String importedFrom() { return importedFrom == null ? "" : importedFrom; }
    public void importedFrom(String value) { importedFrom = value == null ? "" : value.trim(); }
    public boolean isImported() { return !importedFrom().isBlank(); }

    /** The Wikidata item of the work this is the text of. */
    public String workQid() { return workQid == null ? "" : workQid; }
    public void workQid(String value) {
        workQid = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public WikipediaArticleClient.Site site() {
        return site == null ? WikipediaArticleClient.Site.WIKISOURCE : site;
    }
    public void site(WikipediaArticleClient.Site value) {
        site = value == null ? WikipediaArticleClient.Site.WIKISOURCE : value;
    }

    /** Page titles in reading order. */
    public List<String> pages() { return List.copyOf(pages); }
    public void pages(List<String> values) {
        pages.clear();
        if (values == null) return;
        for (String value : values) {
            String title = value == null ? "" : value.trim();
            if (!title.isEmpty() && !pages.contains(title)) pages.add(title);
        }
    }

    public TextPassages.Division division() {
        return division == null ? TextPassages.Division.NUMBERED_CHAPTERS : division;
    }
    public void division(TextPassages.Division value) {
        division = value == null ? TextPassages.Division.NUMBERED_CHAPTERS : value;
    }

    /** Whether this text names its work and at least one page. */
    public boolean isConfigured() {
        return !name().isBlank() && WikidataIds.isQid(workQid()) && !pages.isEmpty();
    }

    public String description() {
        return pages.size() + " " + site().label() + " page(s) of " + workQid() + ", by "
                + division().label().toLowerCase(Locale.ROOT);
    }

    public TextSource copy() {
        TextSource copy = new TextSource();
        copy.name = name();
        copy.declarationId = declarationId();
        copy.importedFrom = importedFrom();
        copy.workQid = workQid();
        copy.site = site();
        copy.pages.addAll(pages);
        copy.division = division();
        return copy;
    }
}
