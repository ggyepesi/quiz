package wikidata.explore.model;

/**
 * Additive interpretation of an English Wikipedia category membership for one field.
 * The single {@code <value>} placeholder names the part resolved through the matching
 * Wikipedia article to a Wikidata entity. Category sources supplement the primary field
 * source; they never replace it.
 *
 * <p>English is not a setting. The article reader, the sitelink it follows and the
 * evidence it records are all enwiki by construction, so a language field here would be
 * a choice with no effect — a knob that lies. Models saved while one existed still load;
 * the property is simply no longer read.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class WikipediaCategoryRule {
    private String pattern = "";
    private CategoryCandidatePolicy policy = CategoryCandidatePolicy.REVIEW;
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    private final java.util.List<WikipediaCategoryMapping> mappings =
            new java.util.ArrayList<>();

    public String pattern() { return pattern == null ? "" : pattern; }
    public void pattern(String value) { pattern = value == null ? "" : value.trim(); }
    public CategoryCandidatePolicy policy() {
        return policy == null ? CategoryCandidatePolicy.REVIEW : policy;
    }
    public void policy(CategoryCandidatePolicy value) {
        policy = value == null ? CategoryCandidatePolicy.REVIEW : value;
    }

    public java.util.List<WikipediaCategoryMapping> mappings() { return mappings; }

    /** Exact mappings win over the title pattern because they are the more specific
     * authored statement.  A pattern match returns a Wikipedia title to resolve; an
     * exact match returns the already configured Wikidata identity. */
    public Match match(String category) {
        if (category == null) return null;
        for (WikipediaCategoryMapping mapping : mappings) {
            if (mapping != null && mapping.matches(category)) {
                return new Match(mapping.valueQid(), "", mapping.category());
            }
        }
        if (!patternConfigured()) return null;
        int placeholder = pattern().indexOf("<value>");
        String prefix = pattern().substring(0, placeholder);
        String suffix = pattern().substring(placeholder + "<value>".length());
        String clean = WikipediaCategoryMapping.cleanCategory(category);
        if (!clean.startsWith(prefix) || !clean.endsWith(suffix)
                || clean.length() < prefix.length() + suffix.length()) return null;
        String title = clean.substring(prefix.length(), clean.length() - suffix.length())
                .trim();
        return title.isBlank() ? null : new Match("", title, clean);
    }

    public boolean patternConfigured() {
        return pattern().split("<value>", -1).length - 1 == 1;
    }

    public boolean configured() {
        return patternConfigured() || mappings.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(WikipediaCategoryMapping::configured);
    }
    /**
     * This rule as the provider-neutral recipe states it. The one model-to-recipe
     * conversion: the field binding and the curation recipe both write through it, so
     * they cannot drift in what they keep (#463). Only configured mappings are written;
     * a pattern without exactly one {@code <value>} is written as none.
     */
    public java.util.Map<String, String> recipeParameters() {
        java.util.List<datasource.wikipedia.WikipediaDatasourceProvider.CategoryMapping>
                exact = mappings.stream().filter(java.util.Objects::nonNull)
                        .filter(WikipediaCategoryMapping::configured)
                        .map(value -> new datasource.wikipedia.WikipediaDatasourceProvider
                                .CategoryMapping(value.category(), value.valueQid()))
                        .toList();
        return datasource.wikipedia.WikipediaDatasourceProvider.categoryParameters(
                new datasource.wikipedia.WikipediaDatasourceProvider.CategoryRule(
                        patternConfigured() ? pattern() : "", policy().name(), exact));
    }

    /**
     * The rule a category binding states, or null when it states no configured rule. The
     * one recipe-to-model conversion, the inverse of {@link #recipeParameters()}; an
     * unknown policy reads as the reviewed default.
     */
    public static WikipediaCategoryRule fromRecipe(datasource.api.SourceBinding binding) {
        datasource.wikipedia.WikipediaDatasourceProvider.CategoryRule recipe =
                datasource.wikipedia.WikipediaDatasourceProvider.categoryRule(binding);
        if (recipe == null) return null;
        WikipediaCategoryRule rule = new WikipediaCategoryRule();
        rule.pattern(recipe.pattern());
        recipe.mappings().forEach(value -> rule.mappings.add(
                new WikipediaCategoryMapping(value.category(), value.valueQid())));
        try {
            rule.policy(CategoryCandidatePolicy.valueOf(recipe.policy()));
        } catch (RuntimeException unknown) {
            rule.policy(CategoryCandidatePolicy.REVIEW);
        }
        return rule;
    }

    /** Takes {@code other}'s content, for a holder that owns this rule instance. */
    public void assign(WikipediaCategoryRule other) {
        WikipediaCategoryRule source = other == null ? new WikipediaCategoryRule() : other;
        pattern(source.pattern());
        policy(source.policy());
        mappings.clear();
        source.mappings.stream().filter(java.util.Objects::nonNull)
                .map(WikipediaCategoryMapping::copy).forEach(mappings::add);
    }

    public WikipediaCategoryRule copy() {
        WikipediaCategoryRule copy = new WikipediaCategoryRule();
        copy.pattern(pattern()); copy.policy(policy());
        mappings.stream().filter(java.util.Objects::nonNull)
                .map(WikipediaCategoryMapping::copy).forEach(copy.mappings::add);
        return copy;
    }

    public record Match(String valueQid, String valueTitle, String category) {
        public Match {
            valueQid = valueQid == null ? "" : valueQid;
            valueTitle = valueTitle == null ? "" : valueTitle;
            category = category == null ? "" : category;
        }
    }
}
