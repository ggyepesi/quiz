package flag;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import aux.CachedImage;
import aux.Constants;
import aux.ResourceFinder;
import quiz.QuizableGroup;
import objectview.media.ImagePane;

import java.util.TreeMap;

public class DownloadShapes {
    static final String isoCodesFile = Constants.flagDataDirectory + "countrycodes.txt";

    // instead of shape of Palau one of its small islands, Angaur is shown
    // instead of shape of the Bahamas one of its small islands, Cat Island is shown
    public static void main(String[] args) throws Exception {
        Map<String, State> states = new TreeMap<>();
        readShapes(states, new QuizableGroup("All"));
    }

    public static void readShapes(Map<String, State> states, QuizableGroup all) throws Exception {
        // Read shapes add them to stateImages
        Map<String, String> isoCodes = readIsoCodes();
        Map<String, String> shapeImageUrls = DownloadShapes.readImageFilenames(isoCodes);
        System.out.println("Reading shapes ");
        QuizableGroup shapeGroup = all.getOrCreateChild("Shape");
        QuizableGroup shapeOnlyGroup = all.getOrCreateChild("ShapeOnly");
        List<Reader> readers = new ArrayList<>();
        for (Entry<String, String> e : shapeImageUrls.entrySet()) {
            Reader reader =
                    new Reader(states.computeIfAbsent(e.getKey(), s -> new State(e.getKey())), e.getValue());
            readers.add(reader);
            reader.start();
        }

        for (Reader reader : readers) {
            reader.join();
            if (!reader.failed()) {
                State state = reader.getSt();
                shapeGroup.addMember(state);
                if (state.getFlagVersions().isEmpty() && state.getArmsVersions().isEmpty()) {
                    shapeOnlyGroup.addMember(state);
                }
            }
        }
    }

    private static class Reader extends Thread {
        private final flag.State st;
        private final String fileName;
        private boolean failed = false;

        public Reader(flag.State st, String fileName) {
            this.st = st;
            this.fileName = fileName;
        }

        public flag.State getSt() {
            return st;
        }
        public boolean failed() {
            return failed;
        }

        public void run() {
            try {
                st.addShape("Shape of ",
                        new ImagePane(st.getName(), st, new CachedImage(fileName, null, true),
                                true, true));
            } catch (Exception e) {
                failed = true;
                throw new RuntimeException(e);
            }
        }
    }

    private static Map<String, String> readIsoCodes() throws Exception {
        BufferedReader reader = Constants.getBufferedReaderForResource(Constants.flagDataDirectory + "countrycodes.txt");
        Map<String, String> isoCodes = new TreeMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) continue;
            String[] tags = line.split(",", 2);
            if (tags.length != 2) continue;
            if (isoCodes.put(tags[0].trim().toLowerCase(), tags[1].trim()) != null) {
                reader.close();
                throw new RuntimeException();
            }
        }
        reader.close();
        return isoCodes;
    }

    private static Map<String, String> readImageFilenames(Map<String, String> nameToIso) throws Exception {
        List<ResourceFinder.ResourceEntry> svgs =
                ResourceFinder.findResources("shapes/svg", "vector.svg");
        Map<String, String> fileToCountry = new TreeMap<>();
        for (var r : svgs) {
            String country = nameToIso.get(r.parentName());
            if (country == null) {
                System.out.println("No country for " + r.parentName());
            }
            fileToCountry.put(country, ResourceFinder.toURL(r.resourcePath()).toString());
        }
        return fileToCountry;
    }
}
