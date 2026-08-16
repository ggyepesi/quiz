package wikidata.explore.query.result;

/**
 * One property found by class-level property discovery: how often it
 * occurred in the sample, its wikibase value type, and an example value.
 *
 * <p>The example is kept in all three of its forms, because what it IS depends on the
 * property: {@code exampleValue} is the value as the query returned it (an entity URI,
 * a Commons file URL, or a plain literal), {@code exampleQid} the entity it denotes
 * when it denotes one, and {@code exampleDisplay} what to read. A view that only kept
 * the display string could not show a media example as the image it is.
 */
public record DiscoveredProperty(
        String pid,
        String label,
        String typeUri,
        String typeLabel,
        PropertyKind kind,
        int count,
        int sampleSize,
        String exampleValue,
        String exampleQid,
        String exampleDisplay,
        String direction) {

    public enum PropertyKind { ENTITY, SCALAR, MEDIA }

    public String frequency() {
        return count + " / " + sampleSize;
    }

    public String fieldName() {
        String s = label == null || label.isBlank() ? pid : label;
        s = s.trim().replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("_+", "_");
        if (s.startsWith("_")) s = s.substring(1);
        if (s.endsWith("_"))   s = s.substring(0, s.length() - 1);
        if (s.isBlank())       s = "field";
        if (Character.isDigit(s.charAt(0))) s = "v_" + s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
