package flag;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import aux.UploadURLParser;
import aux.UrlReader;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class ReadHTMLTable {
    private static final String wiki = "https://en.wikipedia.org/wiki/";
    private static final List<Entry<String, Integer>> tableIndices = Arrays.asList(
            Map.entry("Family_tree_of_the_Greek_gods", 0),
            Map.entry("List_of_official_languages_by_country_and_territory", 1),  //per country
            Map.entry("List_of_official_languages_by_country_and_territory", 3),  //per language
            Map.entry("List_of_natural_satellites", 6),
            Map.entry("List_of_rivers_by_discharge", 2),
            Map.entry("List_of_Nobel_laureates", 1),
            Map.entry("Regions_of_Italy", 1)
        );

    /*
    private static final List<TableDescription> tableDescriptions = Arrays.asList(
        new TableDescription(wiki + "List_of_official_languages_by_country_and_territory", 4,
                            // country, number of official languages, official languages
                            ColumnType.TEXT, ColumnType.INTEGER, ColumnType.LIST,
                            // skip, skip, national languages
                            ColumnType.DUMMY, ColumnType.DUMMY, ColumnType.LIST),
        new TableDescription(wiki + "Family_tree_of_the_Greek_gods", 0,
                            ColumnType.LIST)
    );
    */

    public static void main(String[] args) {
        // The URL of the HTML page you want to parse
        String url = "https://en.wikipedia.org/wiki/List_of_presidents_of_the_United_States";

        try {
            // 1. Fetch the HTML document from the URL
            // Jsoup.connect(url) creates a connection to the specified URL.
            // .get() fetches the document and parses it.
            System.out.println("Connecting to: " + url);
            Document doc = Jsoup.connect(url).get();
            System.out.println("Successfully fetched the page.");

            // 2. Select the first table on the page
            // doc.select("table") gets all <table> elements.
            // .first() gets the first one in the list.
            Element firstTable = doc.select("table").first();

            // Check if a table was found
            if (firstTable == null) {
                System.out.println("No table found on the page.");
                return;
            }

            System.out.println("\n--- Columns of the First Table ---");

            // 3. Iterate through each row of the table
            // firstTable.select("tr") gets all <tr> (table row) elements within the first table.
            Elements rows = firstTable.select("tr");

            // Counter for rows to help with debugging/identification
            int rowCount = 0;

            for (Element row : rows) {
                rowCount++;
                System.out.println("\nRow " + rowCount + ":");

                // 4. Iterate through each column (cell) in the current row
                // row.select("th, td") gets all <th> (table header) and <td> (table data) elements in the row.
                Elements columns = row.select("th, td");

                // Counter for columns in the current row
                int columnCount = 0;

                for (Element column : columns) {
                    columnCount++;
                    // Print the text content of the column
                    System.out.println("  Column " + columnCount + ": " + column.text());

                    // Check if it's the second column to extract image src
                    if (columnCount == 2) {
                        // Select all <img> tags within this second column
                        Elements images = column.select("img");
                        if (!images.isEmpty()) {
                            System.out.println("    Images found in Column " + columnCount + ":");
                            for (Element img : images) {
                                // Get the 'src' attribute of the image
                                // Wikipedia often uses relative URLs for images; prepend the base URL if necessary
                                // Use .absUrl("src") to get the absolute URL if it's relative
                                String absoluteImageUrl = img.absUrl("src");
                                System.out.println("      Image URL: " + absoluteImageUrl);
                            }
                        } else {
                            System.out.println("    No images found in Column " + columnCount + ".");
                        }
                    }
                }
            }

            System.out.println("\n--- End of Table Columns ---");

        } catch (IOException e) {
            // Handle any I/O errors (e.g., network issues, invalid URL)
            System.err.println("Error fetching or parsing the page: " + e.getMessage());
        } catch (Exception e) {
            // Catch any other unexpected errors
            System.err.println("An unexpected error occurred: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    
    public static void main11(String[] args) throws Exception {
        // Parse the HTML
        //Document document = Jsoup.connect("https://en.wikipedia.org/wiki/" + "List_of_official_languages_by_country_and_territory").get();
        Document document = Jsoup.connect("https://en.wikipedia.org/wiki/" + "List_of_presidents_of_the_United_States").get();

        // Select the table from the HTML
        Element table = document.select("table").get(4);

        // Get all rows of the table
        Elements rows = table.select("tr");

        // Iterate through each row
        for (Element row : rows) {
            // Get all columns (cells) in the row
            Elements cols = row.select("th, td");

            // Iterate through each column and print its content
            for (Element col : cols) {
                System.out.print(col.text() + "\t");
            }
            System.out.println(); // New line after each row
        }
    }
        
    public static void main2(String[] args) {
        Entry<String, Integer> e = tableIndices.get(0);
        readTable(wiki + e.getKey(), e.getValue());
    }

    public static void main1(String[] args) throws Exception {
        //readInfobox(document, "Parents");
        //readInfobox(document, "Consorts");
        //readInfobox(document, "Parents");
        //readInfobox(document, "Parents");
        //readInfobox(document, "Parents");
        //readInfobox("Circe");
        readTables("https://en.wikipedia.org/wiki/List_of_mortals_in_Greek_mythology");
    }

    public static void readInfobox(String item) throws Exception {
        Document document = Jsoup.connect("https://en.wikipedia.org/wiki/" + item).get();

        // Select the infobox where the parents' names are likely to be listed
        Element infobox = document.selectFirst("table.infobox");
        if (infobox != null) {
            // Select the rows in the infobox for extracting data
            Elements rows = infobox.select("tr");
            // Loop through each row and find parents' information
            for (Element row : rows) {
                Elements th = row.select("th");
                Elements td = row.select("td");
                if (!th.text().isEmpty()) {
                    if (!td.isEmpty()) {
                        if (!td.text().isEmpty()) {
                            System.out.println(th.text() + " of " + item + ": " + td.text());
                        }
                    }
                }
            }       
        }
    }

    // mythology characters, every second div - ugly but ....
    // there are cases (inmates of tartarus) when the main div contains the list
    // inmates of tartarus is even more complicated - the list (ul li) stands without div!
    // so enlist all the elements, if there is a div with mw-heading class then the next (ul li) is the list
    public static void readTables1(String url) throws Exception {
        Document document = Jsoup.connect(url).get();
        Elements elements = document.select("*");
        // mw-heading gives the title, the next div contains the list
        boolean listFollows = false;
        //List<String> list = new ArrayList<>();
        for (int i = 0; i < elements.size(); ++i) {
            Element element = elements.get(i);
            if (listFollows) {
                System.out.println("Table " + TableDescription.readList(element));
                listFollows = false;
            } else if (element.className().equals("mw-heading mw-heading2")) {
                listFollows = true;
            } else {
                listFollows = false;
            }
        }
    }

    public static void readTables(String url) throws Exception {
        Document document = Jsoup.connect(url).get();
        Elements divs = document.select("div");
        // mw-heading gives the title, the next div contains the list
        boolean listFollows = false;
        for (int i = 0; i < divs.size(); ++i) {
            Element div = divs.get(i);
            if (listFollows) {
                System.out.println("Table " + TableDescription.readList(div));
                listFollows = false;
            } else if (div.className().equals("mw-heading mw-heading2")) {
                System.out.println("Title " + div.select("h2").attr("id"));
                List<String> l = TableDescription.readList(div);
                if (l.isEmpty()) {
                    listFollows = true;
                } else {
                    System.out.println(l.size() + ", " + l);
                    listFollows = true;
                }
            } else {
                listFollows = false;
            }
        }
    }

    public static void readLists(String url) throws Exception {
        Document document = Jsoup.connect(url).get();
        Elements lists = document.select("ul");
        for (int i = 0; i < lists.size(); ++i) {
            Element list = lists.get(i);
            System.out.println("Table " + TableDescription.readList(list));
        }
    }

    public static boolean readTable(String url, int index) {
        System.out.println("Reading " + url + " table " + index);
        try {
            // Fetch the HTML code from the URL
            Document document = Jsoup.connect(url).get();
            // Select all tables on the page
            Elements tables = document.select("table");
            if (tables.size() <= index) {
                System.out.println("Less than " + (index + 1) + " tables found on the page.");
                return false;
            }
            Element table = tables.get(index); 
            Elements rows = table.select("tr");
            System.out.println("There are " + rows.size() + " rows");
            for (Element row : rows) {
                // Select all columns (cells) in the current row
                Elements columns = row.select("td");                
                // If the row contains `th` (header cells), you may want to handle them too
                if (columns.isEmpty()) {
                    System.out.println("HEADER");
                    columns = row.select("th");
                }

                for (Element column : columns) {
                    Element link = column.select("a").first();
                    if (link != null) {
                        String urlValue = link.attr("href"); // Get the URL
                        System.out.print("[" + urlValue + "]\t");
                        if (urlValue.endsWith("svg")) {
                            String uploadUrl = new UrlReader<String>(new UploadURLParser()).read(new URL("https://en.wikipedia.org" + urlValue));            
                            System.out.print("[" + uploadUrl + "]\t");
                        }
                    }  {
                        Elements list = column.select("ul li");
                        if (!list.isEmpty()) {
                        System.out.print("LIST" + list.size() + "[");

                        for (Element l : list) {
                            //String e = l.attr("href");
                            System.out.print(l.text() + "\t");
                        }

                        System.out.print("]\t");
                    }
                    }
                    System.out.print("[" + column.text() + "]\t"); // Use a tab for spacing
                }
                System.out.println();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }

    static void printlinks(Document doc) {
        Elements links = doc.select("a[href]");
        Elements media = doc.select("[src]");
        Elements imports = doc.select("link[href]");

        print("\nMedia: (%d)", media.size());
        for (Element src : media) {
            if (src.nameIs("img"))
                print(" * %s: <%s> %sx%s (%s)",
                        src.tagName(), src.attr("abs:src"), src.attr("width"), src.attr("height"),
                        trim(src.attr("alt"), 20));
            else
                print(" * %s: <%s>", src.tagName(), src.attr("abs:src"));
        }

        print("\nImports: (%d)", imports.size());
        for (Element link : imports) {
            print(" * %s <%s> (%s)", link.tagName(),link.attr("abs:href"), link.attr("rel"));
        }

        print("\nLinks: (%d)", links.size());
        for (Element link : links) {
            print(" * a: <%s>  (%s)", link.attr("abs:href"), trim(link.text(), 35));
        }
    }

    private static void print(String msg, Object... args) {
        System.out.println(String.format(msg, args));
    }

    private static String trim(String s, int width) {
        if (s.length() > width)
            return s.substring(0, width-1) + ".";
        else
            return s;
    }
}

enum ColumnType {
    TEXT,
    INTEGER,
    REAL,
    IMAGE,
    LIST,
    DUMMY
}

class TableDescription {
    private String url;
    private int tableIndex;
    private ColumnType[] rowDesc;
    private int rowSize;

    public TableDescription(String url, int tableIndex, ColumnType ... rowDesc) {
        this.url = url;
        this.tableIndex = tableIndex;
        this.rowDesc = rowDesc;
        rowSize = 0;
        for (ColumnType ct : rowDesc) {
            if (ct != ColumnType.DUMMY) ++rowSize; 
        }
    }

    public String getUrl() {
        return url;
    }

    public int getTableIndex() {
        return tableIndex;
    }

    public ColumnType[] getRowDesc() {
        return rowDesc;
    }

    public Object[] readRow(Element row)throws Exception  {
        Elements cols = row.select("td");  
        if (cols.isEmpty()) return null;

        Object[] columns = new Object[rowSize];
        if (cols.size() < columns.length) {
            throw new Exception("Row has not enough columns: it has " + cols.size() + " columns, expected " + columns.length);
        }
        int i = 0;
        int j = 0;
        for (Element col : cols) {
            if (i >= rowDesc.length) break;
            switch (rowDesc[i]) {
                case DUMMY:
                    ++i;
                    continue;
                case IMAGE:
                    columns[j] = readImageUrl(col);
                    break;
                case INTEGER:
                    columns[j] = readInteger(col);
                    break;
                case LIST:
                    columns[j] = readList(col);
                    break;
                case REAL:
                    columns[j] = readReal(col);
                    break;
                case TEXT:
                    columns[j] = readText(col);
                    break;
                default:
                    break;
            }
            ++i;
            ++j;
        }
        return columns;
    }

    public static String readImageUrl(Element column) throws Exception {
        Element link = column.select("a").first();
        if (link == null) {
            throw new Exception("There is no url for image in " + column);
        }
    
        String urlValue = link.attr("href");
        return urlValue;
        // return new UrlReader<String>(new UploadURLParser()).read(new URL("https://en.wikipedia.org" + urlValue));            
    }

    public static Integer readInteger(Element column) {
        return Integer.parseInt((String)readText(column));
    }

    public static Object readReal(Element column) {
        return Double.parseDouble((String)readText(column));
    }

    public static List<String> readList(Element column) throws Exception {
        Elements listElement = column.select("ul li");
        if (listElement.isEmpty()) {
            List<String> list = new ArrayList<>(1);
            list.add((String)readText(column));
            return list;
        }
        List<String> list = new ArrayList<>(listElement.size());
        for (Element l : listElement) {
            list.add(l.select("a").attr("title"));
            //list.add(l.text());
        }
        return list;
    }

    public static String readText(Element column) {
        return column.select("a").select("title").text();
        //return column.text();
    }
}


