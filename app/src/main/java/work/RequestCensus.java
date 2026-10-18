package work;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What became of every failed endpoint attempt in a run (#397).
 *
 * <p>Attempts are children of the logical work that issued them. That work's terminal
 * status is the fact that decides the outcome: {@link LogStatus#OK} means a retry
 * succeeded, {@link LogStatus#ADAPTED} means smaller work replaced the failed request,
 * and any other outcome means the work gave up. Request text is deliberately irrelevant:
 * equal queries can belong to unrelated work, while an adaptive split necessarily issues
 * different requests.
 */
public record RequestCensus(int failed, List<String> recovered, List<String> gaveUp,
                            int recoveredAttempts) {

    private static final int NAMED = 5;

    public RequestCensus {
        recovered = List.copyOf(recovered);
        gaveUp = List.copyOf(gaveUp);
    }

    public static RequestCensus of(LogNode root) {
        Count count = new Count();
        collect(root, null, count);
        return new RequestCensus(count.failed, new ArrayList<>(count.recovered),
                new ArrayList<>(count.gaveUp), count.recoveredAttempts);
    }

    /** "9 request(s) failed: 7 recovered by retry or split (…); 2 gave up (…)", or
     *  null when no request failed. */
    public String describe() {
        if (failed == 0) return null;
        StringBuilder out = new StringBuilder()
                .append(failed).append(" request(s) failed: ")
                .append(recoveredAttempts).append(" recovered by retry or split");
        if (!recovered.isEmpty()) out.append(" (").append(names(recovered)).append(')');
        int lost = failed - recoveredAttempts;
        if (lost > 0) {
            out.append("; ").append(lost).append(" gave up (")
                    .append(names(gaveUp)).append(')');
        }
        return out.toString();
    }

    private static String names(List<String> names) {
        String shown = String.join(", ", names.subList(0, Math.min(NAMED, names.size())));
        return names.size() > NAMED ? shown + " … and " + (names.size() - NAMED) + " more"
                : shown;
    }

    private static void collect(LogNode node, LogNode parent, Count count) {
        if (node == null) return;
        if (node.kind() == LogKind.REQUEST && node.status() == LogStatus.FAILED) {
            count.failed++;
            String name = ownerName(parent, node);
            if (parent != null && parent.kind() != LogKind.WORKFLOW
                    && (parent.status() == LogStatus.OK
                    || parent.status() == LogStatus.ADAPTED)) {
                count.recoveredAttempts++;
                count.recovered.add(name);
            } else {
                count.gaveUp.add(name);
            }
        }
        for (LogNode step : node.steps()) collect(step, node, count);
    }

    private static String ownerName(LogNode owner, LogNode attempt) {
        if (owner != null && owner.kind() != LogKind.WORKFLOW
                && owner.title() != null && !owner.title().isBlank()) {
            return owner.title();
        }
        return attempt.title() == null || attempt.title().isBlank()
                ? "Unnamed request" : attempt.title();
    }

    private static final class Count {
        private int failed;
        private int recoveredAttempts;
        private final Set<String> recovered = new LinkedHashSet<>();
        private final Set<String> gaveUp = new LinkedHashSet<>();
    }
}
