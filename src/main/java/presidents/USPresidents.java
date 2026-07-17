package presidents;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aux.Constants;
import objectview.viewconfig.DomainViews;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import aux.CachedImage;
import aux.FlexibleDate;
import quiz.QuizableGroup;
import objectview.render.GroupView;
import objectview.media.ImagePane;
import quiz.Quizable;

public class USPresidents implements DomainViews {
    private static final String url = Constants.wiki  + "List_of_presidents_of_the_United_States";
    
    private static final Pattern NAME_DATE_PATTERN = Pattern.compile(
        "^(.+?)\\s*\\((?:b\\.\\h*)?(\\d{4})(?:[–-](\\d{4}))?\\)\\s*(?:\\[(\\d+)\\])?\\s*(\\d+(?:st|nd|rd|th)\\s+term)?\\s*$"
    );

    private final Map<String, President> presidentsByName = new TreeMap<>();
    private final QuizableGroup root = new QuizableGroup("all");
    private final HashMap<List<Integer>, List<ImagePane>> imagePanesBySize = new HashMap<>();

    public static void main(String[] args) throws Exception {
        new USPresidents().show();
    }

    public void show() throws Exception {
        buildViews();
        new GroupView(root).showFrame();
    }

    public Collection<President> getPresidents() {
        return presidentsByName.values();
    }

    public void buildViews() throws Exception {
        // The URL of the HTML page you want to parse
        Document doc = Jsoup.connect(url).get();
        // Select the first table on the page
        Element firstTable = doc.select("table").first();
        if (firstTable == null) {
            System.out.println("No table found on the page.");
            return;
        }
        Elements rows = firstTable.select("tr");
        int rowCount = 0;
        for (Element row : rows) {
            System.out.println("\nRow " + rowCount + ":");

            Elements columns = row.select("th, td");
            if (rowCount > 0) {
              addPresident(columns);
            }
            rowCount++;
        }
    }

    private void addPresident(Elements columns) throws Exception {
        // column 0; number
        int number = 0;
        // column 1: portrait
        String imageUrl = null;

        // column 2: name, (birth, death)
        
        // column 3: term
        FlexibleDate termStartDate = null;
        FlexibleDate termEndDate = null;

        // column 4: color, skip
        // column 5: party name (maybe emty)
        String party = null;

        // column 6: election years (maybe empty)
        // column 7: vice presidents

        Person person = null;
        int columnCount = 0;
        for (Element column : columns) {
            switch (columnCount) {
                case 0:
                    System.out.println(".   Parse number from " + column.text());
                    try {
                        number = Integer.parseInt(column.text());
                    } catch (Exception x) {
                        System.out.println("Couldn't parse number");
                        return;
                    }
                    break;
                case 1:
                    Elements images = column.select("img");
                    if (!images.isEmpty()) {
                        for (Element img : images) {
                            imageUrl = img.absUrl("src");
                        }
                    } else {
                        System.out.println("Portrait NOT FOUND: " + columnCount);
                    }
                    break;
                case 2:
                    person = parseNameAndBirthDeathDates(stripReferences(column.text()));
                    if (imageUrl != null) {
                        person.setPortrait(getProtraitImagePane(person, imageUrl));
                    }
                    break;
                case 3:
                    String termYearsRaw = stripReferences(column.text());
                    termYearsRaw = termYearsRaw.replaceAll("\\[[0-9]+\\]", "").trim();
                    String[] dateParts = termYearsRaw.split("–|\\s-\\s");
                    termStartDate = parseDayMonthYear(dateParts[0].trim());
                    termEndDate = parseDayMonthYear(dateParts[1].trim());
                    break;
                case 4:
                    break;
                case 5:
                    party = stripReferences(column.text());
                    break;
                case 6:
                    System.out.println("Election years: " + columnCount + ", " + column.text());
                    break;
                case 7:
                    System.out.println("Vice presidents: " + columnCount + ", " + column.text());
                    System.out.println("Vice presidents: " + stripReferences(column.text()));
                    break;
                default:
                    System.out.println("Shouldn't be here! " + columnCount);
                    break;
            }
            columnCount++;
        }
        
        if (person != null) {
            President president = presidentsByName.get(person.getName());
            if (president == null) {
                president = new President(person);
                presidentsByName.put(person.getName(), president);
            }
            Term term = new Term(termStartDate, termEndDate);
            term.setNUmber(number);
            term.setParty(party);
            president.addTerm(term);
            root.addMember(president);
        }
    }

    private ImagePane getProtraitImagePane(Quizable quizable, String imageUrl) throws Exception {
        CachedImage cachedImage = new CachedImage(null, imageUrl, false);
        ImagePane imagePane =
                new ImagePane(quizable.getName(), quizable, cachedImage, false, false);

        return imagePane;
    }

    private String stripReferences(String s) {
        // Regex: \[ (literal opening bracket)
        //        .*? (any character, zero or more times, non-greedily)
        //        \] (literal closing bracket)
        return s.replaceAll("\\[.*?\\]", "");
    }

    private FlexibleDate parseDayMonthYear(String monthDayYearString) {
        System.out.println("parseDayMonthYear " + monthDayYearString);
        String[] s = monthDayYearString.split("[,\\s]+");
        if (s.length == 1) {
            return null;
        }
        return new FlexibleDate(Integer.parseInt(s[2]), s[0], Integer.parseInt(s[1]));
    }

    private Person parseNameAndBirthDeathDates(String s) {
        Person person = null;
        Matcher matcher = NAME_DATE_PATTERN.matcher(s);
        if (matcher.matches()) {
            String name = matcher.group(1).trim();
            int i = name.lastIndexOf(' ');

            name = (i == -1)
                    ? name
                    : name.substring(i + 1) + " " + name.substring(0, i);

            String birthYearStr = matcher.group(2);
            String deathYearStr = matcher.group(3); // This will be null if no death year is present
            //String noteReference = matcher.group(4); // New: This will be null if no note reference is present

            FlexibleDate birthDate = null;
            FlexibleDate deathDate = null;

            birthDate = new FlexibleDate(Integer.parseInt(birthYearStr));

            if (deathYearStr != null) {
                deathDate = new FlexibleDate(Integer.parseInt(deathYearStr));
            }
            person = new Person(name);
            person.setBorn(birthDate);
            person.setDied(deathDate);
            System.out.println("Match found for: " + s);

        } else {
            System.out.println("Match not found for: " + s);
        }
        return person;
    }

    @Override
    public GroupView getGroupView() {
        return new GroupView(root);
    }

    @Override
    public Map<String, ? extends Quizable> getViewables() {
        return presidentsByName;
    }
}
