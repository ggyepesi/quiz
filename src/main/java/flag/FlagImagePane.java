package flag;

import aux.CachedImage;
import quiz.Quizable;
import objectview.ImagePane;

/**
 * Flag-specific ImagePane.
 *
 * Same UI behavior as objectview.ImagePane, but it creates FlagCachedImage.
 */
public class FlagImagePane extends ImagePane {

    public FlagImagePane(String title, String url, Quizable quizable, boolean addTitle) throws Exception {
        super(title, url, quizable, addTitle);
    }

    public FlagImagePane(String title, String url, Quizable quizable, boolean addTitle, boolean isSvg) throws Exception {
        super(title, url, quizable, addTitle, isSvg);
    }

    public FlagImagePane(String title, Quizable quizable, CachedImage cachedImage,
                         boolean addListeners, boolean addTitle) throws Exception {
        super(title, quizable, cachedImage, addListeners, addTitle);
    }

    public FlagImagePane(String title, Quizable quizable, CachedImage cachedImage,
                         boolean addListeners) throws Exception {
        super(title, quizable, cachedImage, addListeners);
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
