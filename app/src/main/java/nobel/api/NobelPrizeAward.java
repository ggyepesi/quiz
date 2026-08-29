package nobel.api;

import java.util.List;

/**
 * One Nobel Prize as nobelprize.org awards it: a category in a year, awarded as one or
 * more SHARES.
 *
 * <p>This is the structure Wikidata cannot hold. A P166 statement carries one motivation
 * per laureate, so a prize divided between different achievements has nowhere to record
 * the prize-level motivation, and it ends up attached to one arbitrary laureate — Physics
 * 2018 hangs "for groundbreaking inventions in the field of laser physics" on Mourou but
 * not on Strickland, who shares his half. Here the umbrella is
 * {@link #topMotivation()} and each achievement is a {@link Share}.
 */
public record NobelPrizeAward(
        String categoryCode,
        String category,
        int year,
        String topMotivation,
        List<Share> shares) {

    public NobelPrizeAward {
        shares = List.copyOf(shares);
    }

    /** All laureates of this prize, in the order the source sorts them. */
    public List<Laureate> laureates() {
        return shares.stream().flatMap(share -> share.laureates().stream()).toList();
    }

    /**
     * The laureates who won for ONE achievement, with the motivation they won it for —
     * the {@code LaureatesWithMotivation} of the original Nobel model.
     *
     * <p>{@code portion} is the fraction of the prize the share carries ("1", "1/2",
     * "1/4"), kept as the source states it: it is a statement about how the prize was
     * divided, not a number to compute with.
     */
    public record Share(String motivation, String portion, List<Laureate> laureates) {
        public Share {
            laureates = List.copyOf(laureates);
        }
    }

    /**
     * @param apiId the nobelprize.org laureate id — the join to Wikidata, which records
     *              it as P8024 on 1018 of 1023 laureates.
     * @param organization organizations win the Peace Prize, so a laureate is not always
     *                     a person; the source names them differently and so do we.
     */
    public record Laureate(String apiId, String name, int sortOrder, boolean organization) { }
}
