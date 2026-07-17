package nobel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import aux.Constants;
import aux.Pair;
import objectview.utils.UrlOpener;

// Produces name -> portrait-link for names parsed from nobel prizes with motivation (nobelprizewithmotivation.txt)
// Uses manually fixed names from namesforportraitlinks.txt and looks for name-variations produced by NameVariations for
// enhancing names appearing in nobelprotraitlinks.txt to match names in nobel prize with motivation with those in portrait-links.
public class DownloadPortraits {

    public static Set<String> imageExtensions = Set.of(".jpg", ".jpeg", ".png");

    private final Set<String> names;
    private final Map<String, String> links;

    private final Set<String> noLink = new TreeSet<>();
    private final Map<String, String> linksFound = new TreeMap<>();
    private final Map<String, String> fixToName = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        DownloadPortraits pls = new DownloadPortraits();
        System.out.println(pls);
        System.out.println(pls.noLink.size());
        pls.downloadPortraits();
    }

    public String toString() {
        return "Link " + linksFound.size() + ", no link " + noLink.size();
    }

    public DownloadPortraits() throws Exception {
        links = PortraitLinkExtractor.readPortraitLinks();
        // enhance means that we match against variations and subsets of names in nobelportraitlinks.txt
        // without this there would ~150 missing links for names like
        // Sir Charles Sherrington in nobel prizes and Charles Scott Sherrington in portraits
        // after having enhanced portraits we have Charles Sherrington matching a subset of Sir Charles Sherrington
        enhanceMapWithSubsetsOfVariations(links);
        System.out.println("Enhanced links " + links.size());

        names = readUniqueNames();
        readNameFixes();
        for (String name : names) {
            findLink(name);
        }
    }

    public static void enhanceMapWithSubsetsOfVariations(Map<String, String> links) {
        Map<String, String> enhancement = new TreeMap<>();
        for (Entry<String, String> e : links.entrySet()) {
            for (String var : NameVariations.generateNameVariations(e.getKey())) {
                for (String subset : NameVariations.generateNameSubsets(var)) {
                    enhancement.put(subset, e.getValue());
                }
            }
        }
        links.putAll(enhancement);
    }

    public static Set<String> readUniqueNames() throws Exception {
        String prizeFilename = Constants.nobelDirectory + "nobelprizewithmotivation.txt";
        BufferedReader reader = Constants.getBufferedReaderForResource(prizeFilename);
        String line;
        int numNames = 0;
        Set<String> uniqueNames = new TreeSet<>();
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) break;
            if (NobelPrizes.isSingleYear(line)) continue;  // skip year
            boolean isDomain = false;
            for (Entry<String, NobelPrize.Domain> e : NobelPrizes.domainAndYearStarts.entrySet()) {
                if (line.startsWith(e.getKey())) {
                    isDomain = true;
                    break;
                }
            }
            if (isDomain) continue;  // skip domain
            if (line.startsWith("“")) continue; // skip motivation
            NobelLaureateParser.Result names = NobelLaureateParser.parse(line);
            numNames += names.people.size();
            uniqueNames.addAll(names.people);
        }
        reader.close();
        System.out.println("Read " + numNames + " names, " + uniqueNames.size() + " unique");
        return uniqueNames;
    }

    private void readNameFixes() throws Exception {
        BufferedReader reader = Constants.getBufferedReaderForResource(Constants.nobelDirectory + "namesforportraitlink.txt");
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String parts[] = NameVariations.splitWithoutEmpty(line, "\\s+\\|\\s+");
            fixToName.put(parts[0], parts[1]);
        }
        reader.close();
    }

    private Pair<String, String> getLink(String name) {
        String link = links.get(name);
        return link == null ? null : new Pair<>(name, link);
    }

    private void findLink(String name, String fix) {
        Pair<String, String> nameAndLink = null;
        if ((nameAndLink = getLink(fix)) == null &&
            (nameAndLink = NameVariations.findLinkForVariations(links, fix)) == null &&
            (nameAndLink = NameVariations.findLinkForSubsets(links, fix)) == null &&
            (nameAndLink = NameVariations.findLinkForSubsetsOfVariations(links, fix)) == null) {
            noLink.add(name);
        } else {
            linksFound.put(name, nameAndLink.getY());
        }
    }

    private void findLink(String name) {
        String fix = fixToName.get(name);
        findLink(name, fix == null ? name : fix);
    }

    private String parseImageExtension(String url) {
            int dot = url.lastIndexOf('.');
            if (dot == -1) return null;
        
            String ext = url.substring(dot).toLowerCase();
            return imageExtensions.contains(ext) ? ext : null;
    }

    private void downloadPortraits() throws Exception {
        int error = 0;
        int downloaded = 0;
        int notfound = 0;
        int notImage = 0;
        int noExtension = 0;
        for (String name : names) {

            // filename uses the original unique name
            String jpgFilename = Constants.nobelPortraitsDirectory + name;
            String url = linksFound.get(name);
            if (url == null) {
                System.out.println(name + " not found");
                ++notfound;
                continue;
            }
            String ext = parseImageExtension(url);
            if (ext == null) {
                System.out.println("Not image " + name + ": " + url);
                url = NoImageExtractor.extractImageForName(name);
                if (url == null || (ext = parseImageExtension(url)) == null) {
                    ++notImage;
                    continue;
                }
            }
            jpgFilename += ext;

            if (new File(jpgFilename).exists()) {
                continue;
            } 
            System.out.println(jpgFilename);
            System.out.println("waiting " + name + ", " + url);

            InputStream in = UrlOpener.open(url);
            System.out.println("opened " + url);

            FileOutputStream fos = new FileOutputStream(jpgFilename);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                fos.flush();
            }
            System.out.println(url + " -> " + jpgFilename);
            in.close();;

            fos.close();
            ++downloaded;
        }
        System.out.println("Downloaded " + downloaded + " portraits for " + names.size() + " names");
        System.out.println("There were " + error + " errors, " + notfound + " not found, " + noExtension +
                            " with no extension and not image " + notImage);
    }
}
