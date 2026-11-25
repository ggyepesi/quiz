package domain.enrichment;

import domain.DomainModel;
import domain.DomainSchemas;
import domain.build.SelectedFieldAcquisition;
import domain.build.StandardExternalSourceFamilies;
import domain.config.GeneratedProjectModel;
import domain.curation.Corrections;
import domain.curation.Curatable;
import domain.datasource.EntityRef;
import domain.datasource.api.SourceExecutionPlan;
import domain.datasource.evidence.EvidenceFragment;
import domain.datasource.evidence.ExtractedClaim;
import domain.runtime.WikidataDynamicObject;
import objectview.Viewable;
import objectview.field.FieldKind;
import objectview.field.FieldRef;
import process.Process;
import process.ProcessContext;
import process.ProcessOutcome;
import process.ProcessPlan;
import wikidata.explore.query.logical.StepGenerationLog;
import wikidata.api.WikidataFactStore;
import wikidata.query.core.Datasource;
import wikidata.query.core.WikidataAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Run the selected generation bindings and offer their staged values for ordinary review. */
public record LoadFieldValuesProcess(DomainModel domain, GeneratedProjectModel model,
        String type, String field, SourceExecutionPlan sources, List<Viewable> members)
        implements Process<LoadFieldValuesProcess.Result> {

    public LoadFieldValuesProcess {
        members = List.copyOf(members);
    }

    public record Result(List<EnrichmentProposal> proposals, List<String> notices) { }

    @Override public ProcessPlan plan() {
        return new ProcessPlan("Load " + type + "." + field,
                "Execute the applied field acquisition plan for the selected instances",
                Map.of("members", Integer.toString(members.size()),
                        "sources", sources.summary()), List.of());
    }

    @Override public ProcessOutcome<Result> execute(ProcessContext context) throws Exception {
        var queries = context.queries();
        var endpoints = WikidataAccess.of(queries);
        var stagedMembers = dynamicObjects(members);
        var pool = dynamicObjects(domain.instances());
        var acquired = queries.step("Acquire " + type + "." + field, "Domain", null,
                plan().parameters(), step -> {
                    var log = StepGenerationLog.of(queries, step);
                    var api = endpoints.api().facts(new WikidataFactStore())
                            .cancellation(context.cancellation());
                    api.log(WikidataAccess.structuredRequestLog(Datasource.WIKIDATA, log));
                    try (var ignored = WikidataAccess.logRequests(queries, log)) {
                        return SelectedFieldAcquisition.load(model, sources, field, stagedMembers,
                                pool, StandardExternalSourceFamilies.services(
                                        endpoints.sparqlIfBound(Datasource.WIKIDATA),
                                        endpoints.sparqlIfBound(Datasource.DBPEDIA), api), log,
                                context.cancellation());
                    } finally {
                        api.log(null);
                        api.cancellation(null);
                    }
                });
        var schema = DomainSchemas.resolve(domain, type, field);
        Map<String, List<WikidataDynamicObject>> byId = schema != null && schema.reference()
                ? pool.stream().collect(Collectors.groupingBy(Viewable::getIdentifier)) : Map.of();
        List<EnrichmentProposal> proposals = new ArrayList<>();
        List<String> notices = new ArrayList<>(acquired.failures());
        var curatable = domain.capability(Curatable.class);
        var acceptedValues = Corrections.acceptedFieldCorrections(domain.instances(),
                curatable == null ? null : curatable.curation(), Set.of(field));
        int empty = 0;
        int unchanged = 0;
        int sourceUpdates = 0;
        int unchecked = members.size() - stagedMembers.size();
        for (var read : acquired.reads()) {
            var member = read.member();
            if (read.source() == null) {
                if (read.failed()) continue;
                if (read.answered() && read.value() == null) empty++;
                else unchecked++;
                continue;
            }
            Object value = resolveReferences(read.value(), schema, byId);
            if (value == null) {
                notices.add(member.getDisplayName() + ": no compatible loaded reference for "
                        + read.value());
                continue;
            }
            if (schema != null && schema.collection() && !(value instanceof java.util.Collection<?>)) {
                value = List.of(value);
            }
            var current = member.get(field);
            var evidence = evidence(read, value);
            var accepted = acceptedValues.get(new Corrections.Target(member, field));
            var acceptedSource = accepted == null ? null : accepted.source();
            boolean sameValue = Objects.equals(current, value);
            // A same-value read is a source update only for a curated value whose accepted
            // source no longer matches. A generated value the source still agrees with is
            // confirmed, not a decision: storing it would copy the snapshot into curation.
            if (sameValue && (acceptedSource == null
                    || acceptedSource.matches(read.source(), evidence))) {
                unchanged++;
                continue;
            }
            if (sameValue) sourceUpdates++;
            var action = current == null ? EnrichmentProposal.ReviewAction.FILL_IF_EMPTY
                    : EnrichmentProposal.ReviewAction.REPLACE;
            var candidate = new EnrichmentProposal.FieldCandidate("acquired:" + field, "",
                    field, current, value, read.source(), action,
                    FieldValueCompatibility.problem(domain, schema, value),
                    schema != null && schema.collection(), evidence);
            var subject = new EnrichmentProposal.Subject(member.identityTypeName(),
                    member.getIdentifier(), member.qid(), member.getDisplayName());
            proposals.add(new EnrichmentProposal(subject, List.of(), List.of(candidate), List.of()));
        }
        String summary = (proposals.size() - sourceUpdates) + " changed value(s), "
                + sourceUpdates + " source update(s), " + unchanged + " unchanged, "
                + empty + " examined empty, " + unchecked + " not read";
        notices.addFirst(summary);
        var result = new Result(List.copyOf(proposals), List.copyOf(notices));
        if (acquired.cancelled()) return ProcessOutcome.cancelled(result, summary);
        return acquired.failures().isEmpty() ? ProcessOutcome.succeeded(result, summary)
                : ProcessOutcome.partial(result, null,
                        summary + ". " + String.join(" | ", acquired.failures()));
    }

    private static List<ExtractedClaim> evidence(SelectedFieldAcquisition.Read read, Object value) {
        if (!read.claims().isEmpty()) return read.claims();
        if (value instanceof List<?> values && values.size() == 1) value = values.getFirst();
        if (read.document() == null || !(value instanceof String text) || text.isBlank()
                || read.member().qid().isBlank()) return List.of();
        String property = read.source().propertyId();
        return List.of(new ExtractedClaim(EntityRef.wikidata(read.member().qid()), property,
                text, null, List.of(EvidenceFragment.excerpt(read.document(), property, text)),
                "configured field acquisition", "1", "", 1, List.of()));
    }

    private Object resolveReferences(Object value, FieldRef schema,
            Map<String, List<WikidataDynamicObject>> byId) {
        if (schema == null || schema.valueKind() != FieldKind.REFERENCE) return value;
        if (value instanceof List<?> values) {
            List<?> resolved = values.stream().map(item -> resolveReferences(item, schema, byId))
                    .filter(Objects::nonNull).toList();
            return resolved.size() == values.size() ? resolved : null;
        }
        if (!(value instanceof Viewable reference)) return null;
        var matches = byId.getOrDefault(reference.getIdentifier(), List.of()).stream()
                .filter(member -> FieldValueCompatibility.problem(domain, schema, member) == null)
                .toList();
        if (matches.size() == 1) return matches.getFirst();
        return matches.stream()
                .filter(member -> member.identityTypeName().equals(reference.identityTypeName()))
                .findFirst().orElse(null);
    }

    private static List<WikidataDynamicObject> dynamicObjects(
            java.util.Collection<? extends Viewable> values) {
        return values.stream().filter(WikidataDynamicObject.class::isInstance)
                .map(WikidataDynamicObject.class::cast).toList();
    }
}
