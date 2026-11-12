package commons.discovery;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Label;
import objectview.annotations.Link;
import objectview.annotations.Reference;
import objectview.media.MediaValueData;
import quiz.source.WikidataSource;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;
import java.util.Objects;

/** One Commons file, retaining only structured facts useful during source discovery. */
public final class MediaRepresentation extends ViewableAdapter {
    private final String mediaId;
    @DisplayField private final String caption;
    private final MediaValueData image;
    @Reference @Label("Depicts")
    private final List<WikidataDynamicObject> depictedEntities;
    @Link @Label("Commons source")
    private final String commonsSource;
    private final List<WikidataSource> creators;
    private final List<String> creationDates;
    private final List<WikidataSource> medium;
    private final List<WikidataSource> collections;
    private final List<WikidataSource> locations;

    public MediaRepresentation(
            String mediaId,
            String caption,
            String imageUrl,
            List<WikidataDynamicObject> depictedEntities,
            String commonsSource,
            List<WikidataSource> creators,
            List<String> creationDates,
            List<WikidataSource> medium,
            List<WikidataSource> collections,
            List<WikidataSource> locations) {
        this.mediaId = text(mediaId);
        this.caption = text(caption).isBlank() ? this.mediaId : text(caption);
        this.image = new MediaValueData(this.caption, text(imageUrl),
                wikidata.explore.CommonsMedia.isSvg(imageUrl));
        this.depictedEntities = immutable(depictedEntities);
        this.commonsSource = text(commonsSource);
        this.creators = immutable(creators);
        this.creationDates = immutable(creationDates);
        this.medium = immutable(medium);
        this.collections = immutable(collections);
        this.locations = immutable(locations);
    }

    public String mediaId() { return mediaId; }
    public String caption() { return caption; }
    public MediaValueData image() { return image; }
    public List<WikidataDynamicObject> depictedEntities() { return depictedEntities; }
    public String commonsSource() { return commonsSource; }
    public List<WikidataSource> creators() { return creators; }
    public List<String> creationDates() { return creationDates; }
    public List<WikidataSource> medium() { return medium; }
    public List<WikidataSource> collections() { return collections; }
    public List<WikidataSource> locations() { return locations; }

    @Override public String getIdentifier() { return mediaId; }
    @Override public String getDisplayName() { return caption; }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of()
                : values.stream().filter(Objects::nonNull).distinct().toList();
    }
}
