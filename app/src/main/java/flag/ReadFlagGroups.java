package flag;

import java.io.BufferedReader;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import aux.Constants;
import aux.UploadURLParser;
import aux.UrlReader;
import quiz.ViewableGroup;
import quiz.GroupReader;

public class ReadFlagGroups {
    static boolean readImage = false;
    static boolean debug = false;
    // birds.txt gives countries without prefix like Dominica, Guatemala, etc.
    static final List<String> groupFiles =
        List.of("animals", "astronomical", "birds", "buildings",
                "crescents", "design", "plants", "weapons");

    public static void main(String[] args) throws Exception {
        String filename = groupFiles.get(2);
        String groupName = Character.toUpperCase(filename.charAt(0)) + filename.substring(1);
        filename += ".txt";
        new ReadFlagGroups().curateAndRead(filename, groupName, new ViewableGroup(""), new States());
    }

    public static void curateAndRead(int index, ViewableGroup parent, States download) throws Exception {
        String filename = groupFiles.get(index);
        String groupName = Character.toUpperCase(filename.charAt(0)) + filename.substring(1);
        filename += ".txt";
        new ReadFlagGroups().curateAndRead(filename, groupName, parent, download);
    }

    public static void curateAndReadAll(ViewableGroup parent, States download) throws Exception {
        ReadFlagGroups readGroup = new ReadFlagGroups();
        for (String filename : groupFiles) {
            String groupName = Character.toUpperCase(filename.charAt(0)) + filename.substring(1);
            filename += ".txt";
            readGroup.curateAndRead(filename, groupName, parent, download);
        }
    }

    private static void debug(String msg) {
        if (debug) System.out.println("ReadFlagGroups: " + msg);
    }

    public void curateAndRead(String filename, String groupName, ViewableGroup parent, States downloadedStates) throws Exception {
        GroupReader groupReader = new GroupReader(parent, groupName);
        System.out.println("ReadFlagGroups: reading file " + filename + " to group " +
                            parent.getFullName() + ", " + groupReader.getRoot().getFullName());
        try (BufferedReader reader = Constants.getBufferedReaderForResource(Constants.groupDirectory + filename)) {
            String line;
            String prevLine = "";
            Map<String, Integer> depths = new TreeMap<>();
            boolean parseGroup = true;
            for (; (line = reader.readLine()) != null; prevLine = line) {
                line = line.trim();
                if (line.isEmpty() || line.equals(prevLine)) continue;

                String tags[] = line.split("\t");
                if (tags.length < 2) {
                    parseGroup = false;
                }
                // Build group hierarchy from the beginning of the file.
                if (parseGroup) {
                    String[] levels = tags[0].split("\\.");
                    depths.put(tags[1], levels.length);
                    continue;
                }
                Integer depth = depths.get(line);
                if (depth != null) {
                    String fix = "==";
                    for (int i = 1; i < depth; ++i) {
                        fix += "=";
                    }
                    if (!groupReader.parseGroup(fix + line + fix)) {
                        reader.close();
                        throw new Exception("Should be a group " + line);
                    }
                    //System.out.println("ReadFlagGroups: group " + groupReader.getGroup().getFullName());
                    continue;
                }
                tags = line.split(",");  // check if this works for every design file
                PrefixAndState prefixAndState = PrefixAndState.findPrefix(tags[0]);
                String stateName;
                String prefix = "";
                if (prefixAndState != null) {
                    prefix = prefixAndState.getPrefix();
                    if (!PrefixAndState.startsWithNormalized(line, prefix)) {
                        debug("Line doesn't start with prefix " + line + ", " + prefix);
                        continue;
                    }
                    stateName = prefixAndState.getState();
                } else {
                    debug("Couldn't parse " + line + " try without prefix");
                    stateName = PrefixAndState.canonicalStateName(line);
                }
                State state = downloadedStates.getStates().get(stateName);
                ViewableGroup group = groupReader.getGroup();
                if (state != null) {
                    if (!group.getName().equals("NOGROUP")) {
                        group.addMember(state);
                        state.addGroup(group);
                    }
                } else if (readImage && prefixAndState != null) {
                    String file = prefixAndState.getImageKey();
                    try {
                        String urlString = (Constants.wiki + "File:" + file + ".svg").replace(" ", "_");
                        String uploadUrl = new UrlReader<String>(new UploadURLParser()).read(new URL(urlString));
                        debug("uploadUrl" + urlString + ", " + uploadUrl);
                        if (uploadUrl == null) continue;
                        downloadedStates.readImage(group, prefixAndState, urlString);
                        if (!group.getName().equals("NOGROUP")) {
                            group.addMember(state);
                            state = downloadedStates.getStates().get(stateName);
                            state.addGroup(group);
                        }
                        debug("Read " + file + " to group " + group.getFullName() + " as " + stateName);
                    } catch (Exception e) {
                        System.out.println("ReadFlagGroups: failed " + file + ": " + e.getMessage());
                    }
                }
                prevLine = line;
            }
        } finally {
            System.out.println("ReadFlagGroups: read " + filename + " done.");
        }
    }
}
