package wikidata.explore.tree;

public final class Timing {
    private Timing() {}

    public static String elapsed(long startNanos) {
        long ms =
                (System.nanoTime() - startNanos) / 1_000_000;

        if (ms < 1000) {
            return ms + " ms";
        }

        return String.format("%.2f s", ms / 1000.0);
    }
}
