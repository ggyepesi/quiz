package flag;

import objectview.utils.swing.CachedImage;
import objectview.Viewable;
import objectview.media.ImagePane;

/**
 * Flag-specific ImagePane.
 *
 * Same UI behavior as objectview.media.ImagePane, but it creates FlagCachedImage.
 */
public class FlagImagePane extends ImagePane {

    public FlagImagePane(String title, String url, Viewable viewable, boolean addTitle) throws Exception {
        super(title, url, viewable, addTitle);
    }

    public FlagImagePane(String title, String url, Viewable viewable, boolean addTitle, boolean isSvg) throws Exception {
        super(title, url, viewable, addTitle, isSvg);
    }

    public FlagImagePane(String title, Viewable viewable, CachedImage cachedImage,
                         boolean addListeners, boolean addTitle) throws Exception {
        super(title, viewable, cachedImage, addListeners, addTitle);
    }

    public FlagImagePane(String title, Viewable viewable, CachedImage cachedImage,
                         boolean addListeners) throws Exception {
        super(title, viewable, cachedImage, addListeners);
    }

    @Override
    protected CachedImage createCachedImage(
            String title, String url, boolean isSvg) throws Exception {
        return new FlagCachedImage(title, url, isSvg);
    }

    public static boolean hasImageFile(String title) {
        return FlagCachedImage.hasImageFile(title);
    }
}
