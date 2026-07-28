package quiz.enrichment;

import process.ProcessInputRequest;

import java.util.List;

/** Typed pause: the process has read the sample entity's properties and asks the user to
 *  choose which one sources a field. The UI returns the {@link ChosenProperty}. */
public record PropertySelectionRequest(
        String title,
        String prompt,
        String field,
        List<PropertyOption> options,
        String suggestedPid)
        implements ProcessInputRequest<ChosenProperty> {

    public PropertySelectionRequest {
        options = options == null ? List.of() : List.copyOf(options);
    }

    @Override public Class<ChosenProperty> responseType() {
        return ChosenProperty.class;
    }
}
