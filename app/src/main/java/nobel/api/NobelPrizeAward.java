package nobel.api;

import java.util.List;

/**
 * One Nobel Prize as nobelprize.org awards it: a category in a year, awarded as one or
 * more ACHIEVEMENTS, each cited through one or more laureate awards.
 *
 * <p>This is the structure Wikidata cannot hold. A P166 statement carries one motivation
 * per laureate, so a prize divided between different achievements has nowhere to record
 * the prize-level motivation, and it ends up attached to one arbitrary laureate — Physics
 * 2018 hangs "for groundbreaking inventions in the field of laser physics" on Mourou but
 * not on Strickland, who shares his half. Here the umbrella is
 * {@link #topMotivation()} and each derived achievement is an {@link Achievement}.
 */
public record NobelPrizeAward(
        String categoryCode,
        String category,
        int year,
        String topMotivation,
        List<Achievement> achievements) {

    public NobelPrizeAward {
        achievements = List.copyOf(achievements);
    }

    /** Every source laureate entry, in its explicit source sort order. */
    public List<LaureateAward> laureateAwards() {
        return achievements.stream()
                .flatMap(achievement -> achievement.laureateAwards().stream())
                .sorted(java.util.Comparator.comparingInt(LaureateAward::sortOrder))
                .toList();
    }

    /**
     * One achievement derived by grouping source laureate entries carrying the same
     * non-empty motivation. The source does not identify an achievement separately.
     */
    public record Achievement(String motivation, List<LaureateAward> laureateAwards) {
        public Achievement {
            laureateAwards = List.copyOf(laureateAwards);
        }
    }

    /**
     * One laureate entry exactly as the prize response awards it. The portion belongs
     * here: two laureates cited for one achievement can each receive {@code 1/4}.
     *
     * @param apiId the nobelprize.org laureate id — the join to Wikidata, which records
     *              it as P8024 on 1018 of 1023 laureates.
     * @param organization organizations win the Peace Prize, so a laureate is not always
     *                     a person; the source names them differently and so do we.
     */
    public record LaureateAward(
            String apiId,
            String name,
            String portion,
            int sortOrder,
            boolean organization) { }
}
