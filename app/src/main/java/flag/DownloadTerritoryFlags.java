package flag;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DownloadTerritoryFlags {
    static final String galleryUrl = "https://en.wikipedia.org/wiki/Gallery_of_flags_of_dependent_territories?action=raw";

    // String s1 = "|{{getalias2|French Southern and Antarctic Lands|flag alias}}|[[Flag of the French Southern and Antarctic Lands|Flag of the Administrator]] of the ''[[French Southern and Antarctic Lands]]''<br />(2005–present)";
    static final Pattern galleryFlag = Pattern.compile("\\{\\{getalias2\\|(?<flag>.+)\\|flag alias");
    static final Pattern galleryGroup = Pattern.compile("\\=+(?<group>[^\\=]+)\\=+");

    static final String listUrl = "https://en.wikipedia.org/wiki/Dependent_territory?action=raw";
    static final Pattern listGroup = Pattern.compile("\\=+(?<group>[^\\=]+)\\=+");

    static final Pattern listFlag = Pattern.compile("(\\|\\s*\\{\\{flag\\|(?<flag1>[^\\}^\\|]+))|(\\|\\s*''\\{\\{flag\\|(?<flag2>[^\\\\}^\\\\|]+))");

    public static void main(String[] args) throws Exception {
        //readFlagsOfDependentTerritories(galleryUrl, galleryFlag, galleryGroup);
        //readFlagsOfDependentTerritories(listUrl, listFlag, listGroup);
        diff();
    }

    static void test () {
        String l = "| {{flag|Saint Pierre and Miquelon|local}}";
        Matcher m = listFlag.matcher(l);
        if (m.find()) {
            System.out.println(m.group("flag"));
        }
    }

    /**
     * Reads flags of dependent territories.
     * Copy paste them from terminal to territoryflags.txt so that DownloadFlags can use them.
     * @throws Exception
     */
    // | [[File:Coat of arms of the Commonwealth of Puerto Rico.svg|120x120px]]<br/>[[Coat of arms of Puerto Rico|Details]]
    public static void readFlagsOfDependentTerritories(String url, Pattern flagPattern, Pattern groupPattern) throws Exception {
        InputStream stream = new URL(url).openStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
        String line;
        int n = 0;
        while ((line = reader.readLine()) != null) {
            Matcher matcher = flagPattern.matcher(line);
            if (matcher.find()) {
                ++n;
                //System.out.println(line);
                String flag = matcher.group("flag1");
                if (flag == null) {
                    flag = matcher.group("flag2");
                }
                String u = "|[[File:Flag of " + flag + ".svg]]";
                System.out.println(u);
            } else if (groupPattern != null) {
                matcher = groupPattern.matcher(line);
                if (matcher.find()) {
                    System.out.println("Group " + matcher.group("group"));
                }
            }
        }
        System.out.println("Number of territories " + n);
        reader.close();
    }

    static Set<String> read(String filename) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader(filename));
        Set<String> lines = new TreeSet<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isEmpty()) {
                if (lines.contains(line)) {
                    System.out.println("Duplicate " + line);
                }
                lines.add(line);
            }
        }
        reader.close();
        return lines;
    }

    static void diff() throws Exception {
        final String filename1 = "/Users/gyorgygyepesi/vsprojects/quiz/src/flag/territoryflags.txt";
        final String filename2 = "/Users/gyorgygyepesi/vsprojects/quiz/src/flag/territoriesfromlist.txt";

        Set<String> lines1 = read(filename1);
        Set<String> lines2 = read(filename2);

        System.out.println("Only in " + filename1);
        printDiff(lines1, lines2);
        System.out.println("Only in " + filename2);
        printDiff(lines2, lines1);
    }

    static void printDiff(Set<? extends Object> s1, Set<? extends Object> s2) {
        for (Object e : s1) {
            if (!s2.contains(e)) {
                System.out.println("  " + e);
            }
        }
    }
}
