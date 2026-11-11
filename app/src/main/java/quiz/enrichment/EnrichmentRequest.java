package quiz.enrichment;

import datasource.enrichment.EnrichmentProposal;

import datasource.SourceRef;

import java.util.List;

/** Context supplied to enrichment providers for one field of one subject. */
public record EnrichmentRequest(
        EnrichmentProposal.Subject subject,
        String targetField,
        boolean collection,
        List<SourceRef> sources,
        objectview.field.FieldRef targetSchema,
        Object currentValue,
        List<datasource.evidence.CategoryMembership> categoryMemberships,
        datasource.evidence.ArticleIntroduction articleIntroduction,
        datasource.evidence.WikipediaArticle wikipediaArticle,
        datasource.evidence.InfoboxParameters infoboxParameters) {

    public EnrichmentRequest(EnrichmentProposal.Subject subject, String targetField,
                             boolean collection, List<SourceRef> sources,
                             objectview.field.FieldRef targetSchema, Object currentValue,
                             List<datasource.evidence.CategoryMembership> categoryMemberships,
                             datasource.evidence.ArticleIntroduction articleIntroduction,
                             datasource.evidence.WikipediaArticle wikipediaArticle) {
        this(subject, targetField, collection, sources, targetSchema, currentValue,
                categoryMemberships, articleIntroduction, wikipediaArticle, null);
    }

    public EnrichmentRequest(EnrichmentProposal.Subject subject, String targetField,
                             boolean collection, List<SourceRef> sources,
                             objectview.field.FieldRef targetSchema, Object currentValue,
                             List<datasource.evidence.CategoryMembership> categoryMemberships,
                             datasource.evidence.ArticleIntroduction articleIntroduction) {
        this(subject, targetField, collection, sources, targetSchema, currentValue,
                categoryMemberships, articleIntroduction, null, null);
    }

    public EnrichmentRequest(EnrichmentProposal.Subject subject, String targetField,
                             boolean collection, List<SourceRef> sources,
                             objectview.field.FieldRef targetSchema, Object currentValue,
                             List<datasource.evidence.CategoryMembership> categoryMemberships) {
        this(subject, targetField, collection, sources, targetSchema, currentValue,
                categoryMemberships, null, null);
    }

    public EnrichmentRequest(EnrichmentProposal.Subject subject, String targetField,
                             boolean collection, List<SourceRef> sources,
                             objectview.field.FieldRef targetSchema, Object currentValue) {
        this(subject, targetField, collection, sources, targetSchema, currentValue,
                List.of(), null);
    }

    public EnrichmentRequest(EnrichmentProposal.Subject subject, String targetField,
                             boolean collection,
                             List<SourceRef> sources) {
        this(subject, targetField, collection, sources, null);
    }

    public EnrichmentRequest(EnrichmentProposal.Subject subject, String targetField,
                             boolean collection,
                             List<SourceRef> sources,
                             objectview.field.FieldRef targetSchema) {
        this(subject, targetField, collection, sources, targetSchema, null);
    }

    public EnrichmentRequest {
        sources = sources == null ? List.of() : List.copyOf(sources);
        categoryMemberships = categoryMemberships == null
                ? List.of() : List.copyOf(categoryMemberships);
    }
}
