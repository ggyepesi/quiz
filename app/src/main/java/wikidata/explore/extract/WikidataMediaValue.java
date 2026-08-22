package wikidata.explore.extract;

import objectview.media.MediaValueData;

/**
 * Metadata-only media value.
 * Does not download or decode images.
 *
 * <p>Implements the generic {@link MediaValue} so the renderer can turn it
 * into an image without depending on wikidata — the conversion happens at render
 * time, so this value stays serializable in the data pool.
 */
public class WikidataMediaValue extends MediaValueData {

    public WikidataMediaValue() {
        this("", "", false);
    }

    public WikidataMediaValue(String label, String url, boolean svg) {
        super(label, url, svg);
    }
}
