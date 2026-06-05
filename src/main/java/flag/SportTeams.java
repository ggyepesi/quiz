package flag;

import java.io.BufferedReader;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import aux.Constants;
import aux.UploadURLParser;
import aux.UrlLineProcessor;
import aux.UrlReader;
import presidents.USPresidents;
import quiz.QuizableGroup;
import quiz.ui.QuizableGroupView;
import quiz.ui.ImagePane;
import quiz.Quizable;

import quiz.ui.QuizableViews;

public class SportTeams implements QuizableViews {
    static final char[] ends = new char[] {'*', '†'};

    static final Set<String> canada = Set.of("Alberta", "British Columbia", "Manitoba", "Ontario", "Quebec");

    static final boolean downloadSvgs = false;

    private QuizableGroupView groupView;
    private Map<String, Quizable> quizables;
    private QuizableGroup rootGroup;

    // add city and arena
    // strip '_(x)' from end of name, it stands for disambiguation for wiki (like in Athlethics_(baseball))
    public static void main(String[] args) throws Exception {
        SportTeams logos = new SportTeams();
        logos.buildViews();
        logos.getGroupView().showFrame();
    }

    @Override
    public Map<String, Quizable> getQuizables() {
        return quizables;
    }
    
    @Override
    public QuizableGroupView getGroupView() {
        return groupView;
    }

    @Override
    public void buildViews() throws Exception {
        quizables = new TreeMap<>();
        rootGroup = new QuizableGroup("All");
        List<String> files = List.of("nba", "nfl", "nhl", "mlb");
        for (String file : files) {
            readLogos(file);
        }
        System.out.println("Root " + rootGroup.getChildren().size() + ", " + rootGroup);
        groupView = new QuizableGroupView(rootGroup);
    }

    public void readLogos(String filename) throws Exception {
        QuizableGroup group = rootGroup.getOrCreateChild(filename);
        // stadiums will remain empty, it is added to teams as group
        QuizableGroup countryGroup = rootGroup.getOrCreateChild("Country");
        QuizableGroup quizableGroup = rootGroup.getOrCreateChild("State");
        QuizableGroup cityGroup = rootGroup.getOrCreateChild("Cities");
        QuizableGroup stadiums = new QuizableGroup("Stadium");
        Constants.setSvgDirectory(Constants.logoSvgDirectory);
        System.out.println("Reading file " + filename);
        BufferedReader reader = Constants.getBufferedReaderForResource(Constants.logoDirectory + filename + ".txt");
        String league = filename.toUpperCase();
        String line;
        while ((line = reader.readLine()) != null) {
            String tags[] = line.split("\t");
            boolean found = true;
            while (found) {
                found = false;
                for (char c : ends) {
                    if (tags[0].charAt(tags[0].length() - 1) == c) {
                        tags[0] = tags[0].substring(0, tags[0].length() - 1);
                        found = true;
                    }
                }
            }
            String name = tags[0];
            if (downloadSvgs) {
                downloadSvg(name, line);
                continue;
            }

            String[] cityAndState = tags[1].split(",");
            String city = cityAndState[0].trim();
            String state = cityAndState[1].trim();
            String quizable = cityAndState[1].trim();
            String country = canada.contains(quizable) ? "Canada" : "USA";
            String stadium = tags[2];
            System.out.println(city + ", " + country + ", " + quizable + ", " + stadium);

            SportTeam team = new SportTeam(name);
            ImagePane imagePane = new ImagePane(name, null, team, true);
            team.setLogo(imagePane);

            if (quizables.put(name, team) != null) {
                System.out.println("DUPLICATE " + name);
            }
            group.addMember(team);
            countryGroup.getOrCreateChild(country).addMember(team);
            quizableGroup.getOrCreateChild(quizable).addMember(team);
            cityGroup.getOrCreateChild(city).addMember(team);
            stadiums.getOrCreateChild(stadium).addMember(team);
            team.setCapital(city);
            team.setLeague(league);
            team.setStadium(stadium);
            team.setState(state);
            //team.setCountry(country);
        }
        reader.close();
        System.out.println("Read done file " +  filename + ", " + quizables.size() + " teams, " +
                            rootGroup.getChildren().size() + " groups, " + rootGroup + ", " + group.getMembers().size() + " group members");
    }

    private static void downloadSvg(String name, String line) throws Exception {
        String url = Constants.wiki + name;
        url = url.replace(" ", "_") + "?action=raw";

        String logoUrl = new UrlReader<String>(new LogoLineProcessor()).read(url);
        if (logoUrl == null) {
            System.out.println("LogoUrl null " + line);
            return;
        }
        logoUrl = Constants.wiki + (logoUrl.startsWith("File:") ? logoUrl : ("File:" + logoUrl));
        logoUrl = logoUrl.replace(" ", "_");
        System.out.println("Logo url " + logoUrl);

        String uploadUrl = new UrlReader<String>(new UploadURLParser()).read(logoUrl);
        System.out.println("Read uploadUrl for " + url + ": " + uploadUrl);
        String svgFilename = Constants.getSvgDirectory() + name + ".svg";

        States.downloadSvg(uploadUrl, svgFilename);
    }
}

class LogoLineProcessor implements UrlLineProcessor<String> {
    static final String[] logoStarts = new String[] {
        "| logo_image = <!--Keep old logo until new logo is uploaded properly.-->",
        "| logo_image = ",
        "| logo = "};

    String logo = null;

    @Override
    public URL processLine(String line) throws Exception {
        for (int i = 0; i < logoStarts.length; ++i) {
            if (line.startsWith(logoStarts[i])) {
                logo = line.substring(logoStarts[i].length());
                int end = logo.indexOf('<');
                if (end != -1) logo = logo.substring(0, end);
                break;
            }
        }
        return null;
    }

    @Override
    public boolean isDone() {
        return logo != null;
    }

    @Override
    public String done() throws Exception {
        return logo;
    }
}
