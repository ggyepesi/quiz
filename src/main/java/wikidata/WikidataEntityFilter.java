package wikidata;

public interface WikidataEntityFilter {
    boolean accept(WikidataEntity e);

    static WikidataEntityFilter usefulName() {
        return e -> {
            if (e == null) return false;

            String n = e.getName();

            return n != null
                    && !n.isBlank()
                    && !n.matches("Q\\d+");
        };
    }
}