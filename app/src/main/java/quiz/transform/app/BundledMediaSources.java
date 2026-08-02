package quiz.transform.app;

import aux.Constants;
import aux.ResourceFinder;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataMediaValue;

import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Read compatibility for snapshots saved while FlagCachedImage lost its bundled
 * resource directory. A bare SVG title is rebound only when that exact local flag
 * resource exists; unrelated media titles remain untouched. */
final class BundledMediaSources {
    private BundledMediaSources() {}

    static void resolve(Collection<? extends WikidataDynamicObject> pool) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        if (pool != null) pool.forEach(value -> resolveValue(value, visited));
    }

    private static void resolveValue(Object value, Set<Object> visited) {
        if (value == null || !visited.add(value)) return;
        if (value instanceof WikidataMediaValue media) {
            resolveMedia(media);
        } else if (value instanceof WikidataDynamicObject object) {
            object.dynamicFieldValues().values()
                    .forEach(child -> resolveValue(child, visited));
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(child -> resolveValue(child, visited));
        } else if (value instanceof Map<?, ?> map) {
            map.values().forEach(child -> resolveValue(child, visited));
        }
    }

    private static void resolveMedia(WikidataMediaValue media) {
        String source = media.url();
        if (!media.svg() || source == null || source.isBlank()
                || source.startsWith("file:") || source.startsWith("http://")
                || source.startsWith("https://")) {
            return;
        }
        String filename = source.startsWith("File:") ? source.substring(5) : source;
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".svg")) {
            filename += ".svg";
        }
        URL bundled = ResourceFinder.toURL(Constants.flagSvgDirectory + filename);
        if (bundled != null) media.url(bundled.toString());
    }
}
