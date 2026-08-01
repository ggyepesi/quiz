package flag;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import aux.*;
import language.CountryLanguagesReader;
import language.Language;
import language.Languages;
import objectview.viewconfig.DomainViews;
import quiz.GroupReader;
import quiz.ViewableGroup;
import objectview.Viewable;

import objectview.media.ImagePane;

import java.util.TreeMap;

public class States implements DomainViews {
    static boolean downloadSvgs = false;

    // state -> {(flag, seal, etc.)->image}
    private final Map<String, State> states = new TreeMap<>();
    private final ViewableGroup root = new ViewableGroup("All");
    private boolean built;

    // state -> {state -> {imageKey starting with "Flag of " -> fullState}}
    private final Map<String, Map<String, String>> flagOfs = new TreeMap<>();

    private static final boolean loadFlags = true;
    private static final boolean loadArms = true;

    public static void main(String[] args) throws Exception {
        States states = new States();
        states.buildViews();
        states.getGroupView().showFrame();
    }

    // For State specific helpers like ReadFlagGroups.
    public Map<String, State> getStates() {
        return states;
    }

    @Override
    public Map<String, ? extends Viewable> getViewables() {
        return states;
    }

    @Override
    public java.util.List<? extends objectview.group.ViewableGroup<?>> getRootGroups() {
        return java.util.List.of(root);
    }

    @Override
    public void buildViews() throws Exception {
        if (built) {
            return; // already built — QuizFactory may invoke this again per "Show"
        }
        Constants.setSvgDirectory(Constants.flagSvgDirectory);

        // Seed the fifty US states as USState (admissionDate lives there, not on State)
        // BEFORE the readers get-or-create states by name, so they augment these objects.
        StateAdmissionDates.seed(states);

        DownloadFlagGroups.readCapitalsAndContinents(
                "capitals.txt", "\t", true, root, states);
        // Default: the full curated set. Override with a smaller file (e.g.
        // flags/curatedtest.txt) for a fast dev/test load that fetches few
        // images: -Dstates.imageFile=flags/curatedtest.txt
        readImagesFromFile(System.getProperty("states.imageFile",
                "flags/curatedflagsandarms.txt"));
        DownloadShapes.readShapes(states);
        cleanFlagOfs();
        if (downloadSvgs) return;
        ReadFlagGroups.curateAndReadAll(root.getOrCreateChild("Flag objects"), this);
        DownloadFlagGroups.readCurrencyGroup("currencies.txt", "\t", states);
        DownloadFlagGroups.readCapitalsAndContinents(
                "capitalsofterritories.txt", "\t", false, root, states);
        DownloadFlagGroups.readCapitalsAndContinents(
                "usstatecapitals.txt", "\t", false, root, states);

        DownloadFlagGroups.downloadColorFlagroups(root, states);
        DownloadFlagGroups.downloadDesignFlagroups(root, states);
        readLanguages();
        removeObsoleteRootGroups();

        System.out.println(root.getChildren().size() + " groups, " +
                                   root.getMembers().size() + " vs. " + states.size());
        mem();
        built = true;
    }

    /**
     * Connect the country-language list to the canonical {@link Language}
     * instances loaded by the language domain. Keeping the shared objects (rather
     * than constructing name-only copies) preserves their details and family
     * references when a States view is saved as a snapshot.
     */
    void readLanguages() throws Exception {
        Languages languageViews = new Languages();
        languageViews.buildViews();
        Map<String, List<String>> languageNamesByCountry =
                CountryLanguagesReader.readCountryLanguages(
                        Constants.languageDirectory + "languages.txt");

        for (Entry<String, List<String>> country :
                languageNamesByCountry.entrySet()) {
            State state = states.get(country.getKey());
            if (state == null) {
                continue;
            }
            for (String languageName : country.getValue()) {
                Language language =
                        languageViews.getLanguage(languageName);
                if (language != null
                        && !state.getLanguages().contains(language)) {
                    state.getLanguages().add(language);
                }
            }
        }
    }

    private void mem() {
        Runtime rt = Runtime.getRuntime();
        long total_mem = rt.totalMemory();
        long free_mem = rt.freeMemory();
        long used_mem = total_mem - free_mem;
        System.out.println("Amount of used memory: " + used_mem / (1024.0 * 1024.0 * 1024.0));
    }

    // Returns fals if downloaded from uploadUrl
    public boolean readImage(ViewableGroup group, PrefixAndState prefixAndState, String url) throws Exception {
        State state;
        synchronized (states) {
            state = states.computeIfAbsent(prefixAndState.getState(),
                                    i -> new State(prefixAndState.getState()));
        }
        if (group != null) {
            synchronized (group) {
                group.addMember(state);
                state.addGroup(group);
            }
        }
        if (prefixAndState.isFlag()) {
            if (containsImage(prefixAndState, state.getFlagVersions())) {
                System.out.println("SKIP DUPLICATE FLAG "
                        + prefixAndState.getOriginalImageKey());
                return true;
            }
        } else {
            if (containsImage(prefixAndState, state.getArmsVersions())) {
                System.out.println("SKIP DUPLICATE ARMS "
                        + prefixAndState.getOriginalImageKey());
                return true;
            }
        }
        String imageKey = prefixAndState.getOriginalImageKey();

        if (FlagCachedImage.hasImageFile(imageKey)) {
            try {
                addImage(prefixAndState, state, new FlagImagePane(imageKey, null
                        , state, true));
                return true;
            } catch (Exception ignored) {}
        }
        String resourcePath = Constants.flagSvgDirectory + imageKey + ".svg";
        if (ResourceFinder.resourceExists(resourcePath)) {
            addImage(prefixAndState, state, new FlagImagePane(imageKey, null,
                                                           state, true));
            return true;
        }

        System.out.println("WIKI FALLBACK:");
        System.out.println("  imageKey         = [" + prefixAndState.getImageKey() + "]");
        System.out.println("  originalImageKey = [" + prefixAndState.getOriginalImageKey() + "]");
        System.out.println("  state            = [" + prefixAndState.getState() + "]");
        System.out.println("  fullState        = [" + prefixAndState.getFullState() + "]");
        System.out.println("  url              = [" + url + "]");

        ResourceFinder.debugResource(
                Constants.flagSvgDirectory
                        + prefixAndState.getImageKey()
                        + ".svg");

        ResourceFinder.debugResource(
                Constants.flagSvgDirectory
                        + prefixAndState.getOriginalImageKey()
                        + ".svg");

        String uploadUrl = null;
        try {
            uploadUrl = new UrlReader<>(new UploadURLParser()).read(new URI(url).toURL());
            if (uploadUrl == null) return false;
            addImage(prefixAndState, state, new FlagImagePane(imageKey,
                                                           uploadUrl, state, true));
            return true;
        } catch (Exception ue) {
            System.out.println("Failed to read image from url for " + "[" + imageKey + "] " + url + ", " +
                    uploadUrl + ": " + ue.getMessage());
            return false;
        }
    }

    private boolean containsImage(
            PrefixAndState candidate, List<ImagePane> imagePanes) {
        for (ImagePane imagePane : imagePanes) {
            PrefixAndState existing =
                    PrefixAndState.findPrefix(imagePane.getKey());
            if (existing != null
                    && candidate.getVersionIdentityKey().equals(
                            existing.getVersionIdentityKey())) {
                return true;
            }
            if (candidate.getOriginalImageKey().equals(imagePane.getKey())) {
                return true;
            }
        }
        return false;
    }

    class ImageReader implements Runnable {
        private final PrefixAndState prefixAndState;
        private final String url;
        private final String line;
        private final ViewableGroup group;

        public ImageReader(PrefixAndState prefixAndState, String url, String line, ViewableGroup group) {
            this.prefixAndState = prefixAndState;
            this.url = url;
            this.line = line;
            this.group = group;
        }

        @Override
        public void run() {
            try {
                if (!readImage(group, prefixAndState, url)) {
                    System.out.println("READIMAGEFAILURE " + line);
                }
            } catch (Exception e) {
                System.out.println("Failed to parallel read image for " + prefixAndState.getImageKey() + ", " + url);
                e.printStackTrace();
            }
        }
    }

    public void readImagesFromFile(String filename) throws Exception {
        // Per-call list: Threads are single-use, so storing them in an instance
        // field made a second buildViews() re-start already-started threads
        // (IllegalThreadStateException). Keep them scoped to this invocation.
        List<Thread> imageReaders = new ArrayList<>();
        System.out.println("Reading images from " + filename);
        BufferedReader reader = Constants.getBufferedReaderForResource(Constants.flagDataDirectory + filename);

        GroupReader groupReader = new GroupReader(root);
        boolean suppressGroupMembership = false;
        String line;
        while ((line = reader.readLine()) != null) {
            // Input sentinel: load the following images, but do not create or assign
            // a group named NOGROUP.
            if ("==NOGROUP==".equals(line.strip())) {
                suppressGroupMembership = true;
                continue;
            }
            if (groupReader.parseGroup(line)) {
                suppressGroupMembership = false;
                continue;
            }
            String[] tags = line.split("\t");
            if (tags.length != 4) {
                continue;
            }
            String flagOfState = tags[0];
            PrefixAndState prefixAndState = new PrefixAndState(tags[1], tags[2], tags[3]);
            String imageKey = prefixAndState.getImageKey();

            String urlString = (Constants.wiki + flagOfState + ".svg").replace(" ", "_");
            if (FlagImagePane.hasImageFile(imageKey) && downloadSvgs) {
                System.out.println("Converted manually " + urlString + ", " + imageKey);
            } else {
                if (downloadSvgs) {
                    String uploadUrl;
                    try {
                        uploadUrl = new UrlReader<>(new UploadURLParser()).read(URI.create(urlString).toURL());
                        downloadSvg(uploadUrl, imageKey);
                    } catch (Exception de) {
                        System.out.println("Couldn't download " + imageKey + ": " + de.getMessage());
                    }
                } else {
                    ViewableGroup group = suppressGroupMembership
                            ? null : groupReader.getGroup();
                    imageReaders.add(new Thread(
                            new ImageReader(prefixAndState, urlString, line, group)));
                }
            }
        }
        int max = 50;
        int last = imageReaders.size() % max;
        int n = 0;
        while (n < imageReaders.size()) {
            int c = imageReaders.size() - n < max ? last : max;
            for (int i = 0; i < c; ++i) {
                imageReaders.get(i + n).start();
            }
            for (int i = 0; i < c; ++i) {
                imageReaders.get(i + n).join();
            }
            n += c;
        }
        // Drop only states with no data at all (stray keys). A flagless territory
        // that still carries groups/capitals/etc. is KEPT, so it survives into the
        // snapshot as a curatable fact — its empty flagVersions is the filterable
        // "no flag" signal (flagVersions IS_EMPTY), not a silent gap.
        List<String> toRemove = new ArrayList<>();
        for (String key : states.keySet()) {
            State state = states.get(key);
            if (!state.hasContent()) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            states.remove(key);
            System.out.println("No content, remove " + key);
        }
        System.out.println("Read images done " + filename);
        reader.close();
    }

    /** Old navigation/presence groups are replaced by search, filters and facets. */
    private void removeObsoleteRootGroups() {
        for (String name : List.of("Shape", "ShapeOnly", "NOGROUP", "ByPrefix")) {
            root.getChildrenMap().remove(name);
        }
    }

    public static void downloadSvg(String url, String filename) throws Exception {
        filename = Constants.getSvgDirectory() + filename + ".svg";
        System.out.println("Downloading " + url + " to " + filename);
        InputStream in = objectview.utils.UrlOpener.open(new URI(url).toURL());
        byte[] bytes = new byte[4096];
        int n;
        File f = new File(filename);
        if (f.exists()) {
            System.out.println("Overwriting existing " + filename);
        }
        OutputStream out = new FileOutputStream(filename);
        while ((n = in.read(bytes)) > 0) {
            out.write(bytes, 0, n);
        }
        out.close();
        in.close();
        System.out.println("Downloaded " + url + " to " + filename);
    }

    private synchronized void addImage(PrefixAndState prefixAndState, State state, ImagePane imagePane) {
        String prefix = prefixAndState.getPrefix();
        String imageKey = prefixAndState.getOriginalImageKey();
        if (PrefixAndState.isFlagPrefix(prefix)) {
            if (loadFlags) {
                flagOfs.computeIfAbsent(state.getName(), s -> new TreeMap<>())
                        .put(prefix, prefixAndState.getCanonicalPrefixForDuplicateCheck());
                //old = state.addFlag(imageKey, imagePane);
                state.addFlag(imageKey, imagePane);
            }
        } else if (loadArms) {
            state.addArms(imageKey, imagePane);
        }
    }

    private void cleanFlagOfs() {
        for (Entry<String, Map<String, String>> flagsOf : flagOfs.entrySet()) {
            if (flagsOf.getValue().size() > 1) {
                State state = states.get(flagsOf.getKey());
                System.out.println("PREFIXES " + " for " + state.getName());
                int i = 0;
                for (Entry<String, String> e : flagsOf.getValue().entrySet()) {
                    System.out.println("  " + e.getKey() + "->" + e.getValue());
                    if (i > 0) {
                        System.out.println("REMOVEFLAG " + e.getValue());
                        //state.getFlagVersions().remove(e.getValue());
                    }
                    ++i;
                }
            }
        }
        flagOfs.clear();
    }
}
