package flag;

import aux.CachedImage;
import aux.Constants;
import aux.ResourceFinder;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * Flag-specific CachedImage.
 *
 * Keeps old flag-specific behavior out of aux.CachedImage:
 * - JPEG fallback map
 * - hard-coded flag/jpeg directory
 * - Constants.getSvgDirectory()
 * - ResourceFinder.toURL(...)
 */
public class FlagCachedImage extends CachedImage {
    private static final String jpegDir = "/Users/gyorgygyepesi/vsprojects/quiz/src/flag/jpeg/";

    private static final Map<String, String> jpegFiles1 = Map.of(
            "Coat of arms of Cyprus", "Coat_of_arms_of_Cyprus_(2006).jpg",
            "Coat of arms of Peru", "Escudo_nacional_del_Per C3 BA.jpg",
            "Coat of arms of Serbia", "Coat_of_arms_of_Serbia.jpg",
            "Coat of arms of Seychelles", "Coat_of_arms_of_Seychelles.jpg",
            "Coat of arms of South Sudan", "Coat_of_arms_of_South_Sudan.jpg",
            "Coat of arms of British Antarctic Territory", "Coat_of_arms_of_the_British_Antarctic_Territory.jpg",
            "Coat of arms of Liechtenstein", "Staatswappen-Liechtensteins.jpg",
            "Coat of arms of Puerto Rico", "Coat_of_arms_of_the_Commonwealth_of_Puerto_Rico.jpg",
            "Coat of arms of Saint Helena", "Coat_of_Arms_of_Saint_Helena.jpg",
            "Coat of arms of Iraq", "Coat_of_arms_of_Iraq.jpg");

    private static final Map<String, String> jpegFiles2 = Map.of(
            "Flag of Easter Islands", "Flag_of_Easter_Islands.jpg",
            "Coat of arms of Tunisia", "Coat_of_arms_of_Tunisia.jpg",
            "Coat of arms of Melilla", "Coat_of_Arms_of_Melilla.jpg",
            "Coat of arms of Iceland", "Coat_of_arms_of_Iceland.jpg",
            "Great Seal of the state of California Colored", "Great_Seal_of_the_State_of_California_Colored.jpg",
            "Great Seal of the state of Kansas Colored", "Great_Seal_of_the_State_of_Kansas_Colored.jpg");

    private static final Map<String, String> jpegFiles = new TreeMap<>();

    static {
        jpegFiles.putAll(jpegFiles1);
        jpegFiles.putAll(jpegFiles2);
    }

    private final String title;
    private final String explicitUrl;
    private final boolean svg;

    public FlagCachedImage(String filename, String url, boolean isSvg) throws Exception {
        super(filename, url, isSvg);
        this.title = filename;
        this.explicitUrl = url;
        this.svg = isSvg;
    }

    /**
     * Requires aux.CachedImage to expose this protected hook.
     */
    @Override
    protected void ensureBytesLoaded() throws Exception {
        if (hasImageBuf()) {
            return;
        }

        // 1. Curated JPEG fallback only for known JPEG exceptions.
        String jpegFilename = jpegFiles.get(title);

        if (jpegFilename != null) {
            try (InputStream in = new FileInputStream(jpegDir + jpegFilename)) {
                setImageBuf(in.readAllBytes());
                setSvg(false);
            }
            return;
        }

        // 2. Curated local SVG/resource for filename-only flag data.
        if (title != null && !title.isBlank()) {
            String filename = title;

            if (svg && !filename.endsWith(".svg")) {
                filename += ".svg";
            }

            if (!filename.startsWith("file:/")) {
                filename = String.valueOf(
                        ResourceFinder.toURL(
                                Constants.getSvgDirectory() + filename));
            }

            readToImageBuf(filename);
            return;
        }

        // 3. Explicit URL only as fallback.
        if (explicitUrl != null && !explicitUrl.isBlank()) {
            readToImageBuf(explicitUrl);
            return;
        }

        throw new IllegalStateException(
                "FlagCachedImage: no title/resource/url");
    }

    public static boolean hasImageFile(String title) {
        return jpegFiles.get(title) != null;
    }
}
