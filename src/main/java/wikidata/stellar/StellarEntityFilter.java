package wikidata.stellar;

import wikidata.WikidataEntity;
import wikidata.WikidataEntityFilter;

public class StellarEntityFilter implements WikidataEntityFilter {
    @Override
    public boolean accept(WikidataEntity e) {
        if (e == null) return false;

        String n = e.getName();

        if (n == null || n.isBlank()) return false;

        n = n.trim();

        if (n.matches("Q\\d+")) return false;

        if (n.matches("^(NGC|IC|HD|HR|HIP|Gaia|2MASS|WISE|WISEA|SDSS|TYC)\\b.*")) {
            return false;
        }

        if (n.matches(".*J\\d{4,}.*")) {
            return false;
        }

        return n.length() <= 80;
    }
}