package misc;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aux.BlockKeeper;

public class ValueParsers {

    public static void main(String[] args) throws Exception {
        BlockKeeper bk = new BlockKeeper();
        ListParser lparser = new ListParser(bk, new DebugListParserListener());
        BufferedReader reader = new BufferedReader(new FileReader("src/s.txt"));
        String line;
        while ((line = reader.readLine()) != null) {
            line = bk.update(line);
            System.out.println("Process " + line);
            lparser.parseLine(line);
        }
        lparser.parseDone();
        reader.close();
    }
}

class DebugListParserListener implements ListParserListener {
    @Override
    public boolean parseType(String type) {
        if (type.indexOf("englishm") == -1) {
            System.out.println("PARSING " + type);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void parseTypeDone(String type, List<QualifiedValue> items) {
        System.out.println("PARSED " + type);
        for (QualifiedValue qv : items) {
            System.out.println("  qv " + qv);
        }
    }

}

// Parser for list items between '{{ and '}}'.
class ListParser {
    // For languages in https://en.wikipedia.org/wiki/Burkina_Faso?action=raw:
    // | official_languages     = French, Mossi (Regional), Fula (Regional) Dyula (Regional) for Burkina_Faso
    private static final Pattern anyTypePattern = Pattern.compile("\\s*\\|\\s*(?<type>[^ ]+)\\s*=");

    private QualifiedValuesParser qualifiedValuesParser = new QualifiedValuesParser();
    private BlockKeeper bk;
    private boolean typeFound = false;
    private String type;  // the type being parsed
    private List<QualifiedValue> listItems = new ArrayList<>();
    private ListParserListener listener;

    public ListParser(BlockKeeper bk, ListParserListener listener) {
        this.bk = bk;
        this.listener = listener;
    }

    public void parseDone() {
        if (typeFound) {
            listener.parseTypeDone(type, listItems);
        }
        listItems.clear();
        typeFound = false;
    }

    public List<QualifiedValue> getListItems() {
        return listItems;
    }

    // Returns the list of list-items found in line.
    public void parseLine(String line) {
        // First check if line is about a type.
        Matcher matcher = anyTypePattern.matcher(line);
        if (matcher.find()) {
            // Finish previous type.
            if (typeFound) {
                parseDone();
            }
            typeFound = listener.parseType(type = matcher.group("type"));
            line = line.substring(matcher.end());
        }
        if (typeFound) {
            listItems.addAll(qualifiedValuesParser.parseValues(line));
            if (bk.blockEndsInCurrentLine(BlockKeeper.LIST)) {
                parseDone();
            }
        }
    }
}

class QualifiedValuesParser {
    private static final String skipPrefix = "[*, ,|,=]*";
    // [[value]] (qualifier)
    private static final String qualifiedValueString1 =
        skipPrefix + "\\[\\[(?<qualifiedValue1>[^]]+)\\]\\]\\s*\\((?<qualifier1>[^)]+)\\)";
     // value (qualifier)
     private static final String qualifiedValueString2 =
        skipPrefix + "(?<qualifiedValue2>[^()]+)\\s*\\((?<qualifier2>[^)]+)\\)";
    // [[value]]
    private static final String valueString = skipPrefix + "\\[\\[(?<value1>[^]]+)\\]\\]";
    // value
    private static final String simpleValueString = skipPrefix + "(?<value2>[^,^)]+)";
    private static final String qualifiedValueString =
        qualifiedValueString1 + "|" + qualifiedValueString2 + "|" + valueString + "|" + simpleValueString;
    private static final Pattern qualifiedValuePattern = Pattern.compile(qualifiedValueString);

    private static final String valuePrefixFormat = "\\|\\s*%s\\s*=\\s*";
    
    public static String getQualifiedValueString() {
        return qualifiedValueString;
    }

    private final Pattern valuePrefixPattern;
    private final boolean mustBeQualified;

    public QualifiedValuesParser() {
        valuePrefixPattern = null;
        mustBeQualified = false;
    }

    public QualifiedValuesParser(String valueName, boolean mustBeQualified) {
        this.mustBeQualified = mustBeQualified;
        valuePrefixPattern = Pattern.compile(String.format(valuePrefixFormat, valueName));
    }

    public List<QualifiedValue> parseLine(String line) {
        Matcher m = valuePrefixPattern.matcher(line);
        if (!m.find() || m.start() != 0) return new ArrayList<>();
        return parseValues(line);
    }

    public  List<QualifiedValue> parseValues(String line) {
        List<QualifiedValue> qualifiedValues = new ArrayList<>();
        Matcher m = qualifiedValuePattern.matcher(line);
        while (m.find()) {
            QualifiedValue qualifiedValue = getQualifiedValue(m);   
            qualifiedValues.add(qualifiedValue);
            // System.out.println("    " + valueName + " " + qualifiedValue);
        }
        // If there are more than one values then they should be qualified.
        if (qualifiedValues.size() > 1 && mustBeQualified) {
            for (int i = qualifiedValues.size() - 1; i >= 0; --i) {
                if (qualifiedValues.get(i).getQualifier().isEmpty()) {
                    qualifiedValues.remove(i);
                }
            }
        }
        return qualifiedValues;
    }

    public QualifiedValue getQualifiedValue(Matcher matcher) {
        String qualifiedValue1 = matcher.group("qualifiedValue1");
        if (qualifiedValue1 != null) {
            return new QualifiedValue(qualifiedValue1, matcher.group("qualifier1"));
        }
        String qualifiedValue2 = matcher.group("qualifiedValue2");
        if (qualifiedValue2 != null) {
            return new QualifiedValue(qualifiedValue2, matcher.group("qualifier2"));
        }
        String value1 = matcher.group("value1");
        if (value1 != null) {
            return new QualifiedValue(value1, "");
        }
        String value2 = matcher.group("value2");
        if (value2 != null) {
            return new QualifiedValue(value2, "");
        }
        return null;
    }
}

class ValueParser {
    private static final String valueFormat = "\\|\\s*%s\\s*=\\s*(?<value>\\[\\[[^]]+\\]\\]\\s*|[^ ]+)\\s*";

    private String valueName;
    private final Pattern valuePattern;
    
    public ValueParser(String valueName) {
        this.valueName = valueName;
        valuePattern = Pattern.compile(String.format(valueFormat, valueName));
    }

    public String parseLine(String line) {
        Matcher m = valuePattern.matcher(line);
        if (!m.find() || m.start() != 0) return null;
        String value = m.group("value");
        System.out.println("  " + valueName + " " + value);
        return value;
    }
}

class QualifiedValue implements Serializable {
    private static final long serialVersionUID = -7724074319025946937L;

    private String value;
    private String qualifier;

    public QualifiedValue(String value) {
        this.value = value.trim();
    }

    public QualifiedValue(String value, String qualifier) {
        this.value = value.trim();
        this.qualifier = qualifier.trim();
    }
    
    public String getValue() {
        return value;
    }
    public void setValue(String value) {
        this.value = value.trim();
    }
    public String getQualifier() {
        return qualifier;
    }
    public void setQualifier(String qualifier) {
        this.qualifier = qualifier.trim();
    }

    public String toString() {
        return qualifier.isEmpty() ? value : value + ", " + qualifier;
    }
}

interface ListParserListener {
    public boolean parseType(String type);
    public void parseTypeDone(String type, List<QualifiedValue> items);
}

