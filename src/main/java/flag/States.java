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
import quiz.GroupReader;
import quiz.QuizableGroup;
import quiz.Quizable;

import objectview.ImagePane;
import objectview.QuizableGroupView;
import objectview.QuizableViews;

import java.util.TreeMap;

public class States implements QuizableViews {
    static boolean downloadSvgs = false;

    // state -> {(flag, seal, etc.)->image}
    private final Map<String, State> states = new TreeMap<>();
    private final QuizableGroup root = new QuizableGroup("All");
    private QuizableGroupView groupView;
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
    public Map<String, ? extends Quizable> getQuizables() {
        return states;
    }

    @Override
    public QuizableGroupView getGroupView() {
        return groupView;
    }

    @Override
    public void buildViews() throws Exception {
        if (built) {
            return; // already built — QuizFactory may invoke this again per "Show"
        }
        Constants.setSvgDirectory(Constants.flagSvgDirectory);

        DownloadFlagGroups.readCapitalsAndContinents(
                "capitals.txt", "\t", true, root, states);
        // Default: the full curated set. Override with a smaller file (e.g.
        // flags/curatedtest.txt) for a fast dev/test load that fetches few
        // images: -Dstates.imageFile=flags/curatedtest.txt
        readImagesFromFile(System.getProperty("states.imageFile",
                "flags/curatedflagsandarms.txt"));
        DownloadShapes.readShapes(states, root);
        cleanFlagOfs();
        if (downloadSvgs) return;
        ReadFlagGroups.curateAndReadAll(root.getOrCreateChild("Objects"), this);
        DownloadFlagGroups.readCurrencyGroup("currencies.txt", "\t", root, states);
        DownloadFlagGroups.readCapitalsAndContinents(
                "capitalsofterritories.txt", "\t", false, root, states);
        DownloadFlagGroups.readCapitalsAndContinents(
                "usstatecapitals.txt", "\t", false, root, states);

        DownloadFlagGroups.downloadColorFlagroups(root, states);
        DownloadFlagGroups.downloadDesignFlagroups(root, states);

        System.out.println(root.getChildren().size() + " groups, " +
                                   root.getMembers().size() + " vs. " + states.size());
        mem();
        groupView = new QuizableGroupView(root);
        built = true;
    }

    private void mem() {
        Runtime rt = Runtime.getRuntime();
        long total_mem = rt.totalMemory();
        long free_mem = rt.freeMemory();
        long used_mem = total_mem - free_mem;
        System.out.println("Amount of used memory: " + used_mem / (1024.0 * 1024.0 * 1024.0));
    }

    // Returns fals if downloaded from uploadUrl
    public boolean readImage(QuizableGroup group, PrefixAndState prefixAndState, String url) throws Exception {
        State state;
        synchronized (states) {
            state = states.computeIfAbsent(prefixAndState.getState(),
                                    i -> new State(prefixAndState.getState()));
        }
        if (group != null && !group.getName().equals("NOGROUP")) {
            synchronized (group) {
                group.addMember(state);
                state.addGroup(group);
            }
        }
        String imageKey = prefixAndState.getOriginalImageKey();
        String canonicalKey = prefixAndState.getCanonicalPrefixForDuplicateCheck();
        if (prefixAndState.isFlag()) {
            if (containsImageKey(canonicalKey, state.getFlagVersions())) {
                System.out.println("SKIP DUPLICATE FLAG " + imageKey);
                return true;
            }
        } else {
            if (containsImageKey(canonicalKey, state.getArmsVersions())) {
                System.out.println("SKIP DUPLICATE ARMS " + imageKey);
                return true;
            }
        }

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

    private boolean containsImageKey(String key, List<ImagePane> imagePanes) {
        for (ImagePane imagePane : imagePanes) {
            if (key.equals(imagePane.getKey())) return true;
        }
        return false;
    }

    class ImageReader implements Runnable {
        private final PrefixAndState prefixAndState;
        private final String url;
        private final String line;
        private final QuizableGroup group;

        public ImageReader(PrefixAndState prefixAndState, String url, String line, QuizableGroup group) {
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
        String line;
        while ((line = reader.readLine()) != null) {
            if (groupReader.parseGroup(line)) {
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
                    imageReaders.add(new Thread(new ImageReader(prefixAndState, urlString, line, groupReader.getGroup())));
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
        // Remove states with no image.
        List<String> toRemove = new ArrayList<>();
        for (String key : states.keySet()) {
            State state = states.get(key);
            if (!state.hasImagePane()) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            states.remove(key);
            System.out.println("No image, remove " + key);
        }
        System.out.println("Read images done " + filename);
        reader.close();
    }

    public static void downloadSvg(String url, String filename) throws Exception {
        filename = Constants.getSvgDirectory() + filename + ".svg";
        System.out.println("Downloading " + url + " to " + filename);
        InputStream in = new URI(url).toURL().openStream();
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
        String imageKey = prefixAndState.getCanonicalPrefixForDuplicateCheck();
        root.getOrCreateChild("ByPrefix").getOrCreateChild(prefix).addMember(state);

        if (PrefixAndState.isFlagPrefix(prefix)) {
            if (loadFlags) {
                flagOfs.computeIfAbsent(state.getName(), s -> new TreeMap<>()).put(prefix, imageKey);
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

