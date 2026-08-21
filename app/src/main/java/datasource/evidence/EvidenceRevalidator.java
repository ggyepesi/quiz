package datasource.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Revalidates persisted claims without coupling curation to HTTP or a particular source.
 * A datasource supplies the current document-version lookup; this service deduplicates
 * documents shared by claims and turns lookup failures into explicit unavailable results.
 */
public final class EvidenceRevalidator {
    private EvidenceRevalidator() { }

    @FunctionalInterface
    public interface DocumentResolver {
        SourceDocument currentVersion(SourceDocument acceptedVersion) throws Exception;
    }

    public record Assessment(ExtractedClaim claim, EvidenceStatus status, String problem) {
        public Assessment {
            claim = Objects.requireNonNull(claim);
            status = Objects.requireNonNull(status);
            problem = problem == null ? "" : problem;
        }
    }

    public static List<Assessment> assess(
            List<ExtractedClaim> claims,
            DocumentResolver resolver,
            String currentRecipeVersion,
            String currentModelFingerprint) {
        Objects.requireNonNull(resolver, "Document resolver is required");
        List<ExtractedClaim> input = claims == null ? List.of() : claims.stream()
                .filter(Objects::nonNull).toList();
        Map<String, SourceDocument> current = new LinkedHashMap<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (ExtractedClaim claim : input) {
            for (EvidenceFragment fragment : claim.evidence()) {
                SourceDocument accepted = fragment.document();
                if (current.containsKey(accepted.documentId())
                        || failures.containsKey(accepted.documentId())) continue;
                try {
                    SourceDocument resolved = resolver.currentVersion(accepted);
                    if (resolved == null) {
                        failures.put(accepted.documentId(), "Source is unavailable");
                    } else {
                        current.put(accepted.documentId(), resolved);
                    }
                } catch (Exception ex) {
                    String message = ex.getMessage();
                    failures.put(accepted.documentId(), message == null || message.isBlank()
                            ? ex.getClass().getSimpleName() : message);
                }
            }
        }
        List<Assessment> result = new ArrayList<>(input.size());
        for (ExtractedClaim claim : input) {
            EvidenceStatus status = claim.statusAgainstDocuments(
                    current, currentRecipeVersion, currentModelFingerprint);
            String problem = claim.evidence().stream()
                    .map(EvidenceFragment::document).map(SourceDocument::documentId)
                    .map(failures::get).filter(Objects::nonNull).findFirst().orElse("");
            result.add(new Assessment(claim, status, problem));
        }
        return List.copyOf(result);
    }
}
