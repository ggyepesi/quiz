package work;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What became of every failed endpoint attempt in a run (#397).
 *
 * <p>Two stored facts decide the outcome, never request text — equal queries can belong
 * to unrelated work, while an adaptive split necessarily issues different requests:
 * <ul>
 *   <li>a later attempt in the same <em>retry chain</em> (the transport's identity for
 *       one request) succeeded — the transport retried it;</li>
 *   <li>the attempt was issued by logical work, and that work ended {@link LogStatus#OK}
 *       or {@link LogStatus#ADAPTED} (replaced by smaller work).</li>
 * </ul>
 * Anything else gave up. An attempt filed under a step with no owning work is never
 * recovered merely because the step ended OK.
 */
public record RequestCensus(int failed, List<String> recovered, List<String> gaveUp,
                            int recoveredAttempts) {

    private static final int NAMED = 5;

    public RequestCensus {
        recovered = List.copyOf(recovered);
        gaveUp = List.copyOf(gaveUp);
    }

    public static RequestCensus of(LogNode root) {
        List<LogNode[]> attempts = new ArrayList<>();   // {attempt, parent}, tree order
        collect(root, null, attempts);
        Count count = new Count();
        for (int i = 0; i < attempts.size(); i++) {
            LogNode attempt = attempts.get(i)[0];
            LogNode parent = attempts.get(i)[1];
            if (attempt.status() != LogStatus.FAILED) continue;
            count.failed++;
            String name = ownerName(attempt.ownedByWork() ? parent : null, attempt);
            if (retriedSuccessfully(attempts, i) || ownerRecovered(attempt, parent)) {
                count.recoveredAttempts++;
                count.recovered.add(name);
            } else {
                count.gaveUp.add(name);
            }
        }
        return new RequestCensus(count.failed, new ArrayList<>(count.recovered),
                new ArrayList<>(count.gaveUp), count.recoveredAttempts);
    }

    /** A later attempt at the same request — its retry chain — succeeded. */
    private static boolean retriedSuccessfully(List<LogNode[]> attempts, int failed) {
        String chain = attempts.get(failed)[0].retryChain();
        if (chain == null) return false;
        for (int i = failed + 1; i < attempts.size(); i++) {
            LogNode later = attempts.get(i)[0];
            if (chain.equals(later.retryChain()) && later.status() == LogStatus.OK) {
                return true;
            }
        }
        return false;
    }

    /** The logical work that issued the attempt ended OK, or was replaced by smaller
     *  work. An attempt merely filed under a step has no such owner: the step ending OK
     *  says nothing about the request, whose error its caller may have swallowed. */
    private static boolean ownerRecovered(LogNode attempt, LogNode parent) {
        return attempt.ownedByWork() && parent != null && parent.kind() != LogKind.WORKFLOW
                && (parent.status() == LogStatus.OK || parent.status() == LogStatus.ADAPTED);
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

    private static void collect(LogNode node, LogNode parent, List<LogNode[]> into) {
        if (node == null) return;
        if (node.kind() == LogKind.REQUEST) into.add(new LogNode[] {node, parent});
        for (LogNode step : node.steps()) collect(step, node, into);
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
