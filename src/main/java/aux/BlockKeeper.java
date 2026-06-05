package aux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Keeps track of open block of the specified types thru lines given to update.
// - find solution for < > blocks
// - keep track of the depth of specified blocks ({{Infobox }}) and remove isInfobox from TextParser - use getDepth(String blockStart).
//   This is needed because of France (a 'child' infobox starts at the end of languages) and Ethiopia (the country infobox starts at the end of a line) so
//   being the prefix of a line doesn't tell anything about being the first infobox.
public class BlockKeeper {
    private Deque<BlockStart> stack = new ArrayDeque<>();

    // Stores all the startPatterns a specific endPattern can be the ending pair of.
    // Use this map as follows:
    // find all the starts for all the startPatterns and endPatterns. Sort the resulting intervals by their left end and process them in this order.
    // Push startPatterns to the stack (a pattern cannot be both end and start pattern). For an endPattern take the last element of the stack. If it
    // is an end pattern then push the endPattern. If it is a startPattern then it must belong to the startPatterns of endPattern - unbalanced line if
    // not. Compute the interval to remove just as in the current version (start is pos of the startPattern if line is the same as the line of the startPattern,
    // 0 otherwise, end is end of the endPattern)
    // Use patterns because toLowercase might change the length of the string
    private static final Map<String, List<String>> startsOfEnds = new TreeMap<>();
    private static final Map<String, String> endOfStarts = new TreeMap<>();
   
    private static final Map<String, Pattern> startEndAndRemovePatterns = new TreeMap<>();
    // If there are block-starting prefixes of block-starts in startsOfEnds then put them to fakeStartsOfEnds so that remove skip them without compromising stack.
    private static final Set<String> blockStarts = new TreeSet<>();
    private static final Set<String> stripStarts = new TreeSet<>();
    // Blocks to keep the first appearance of (like infobox). The condition to keep can be changed in the future, this is enough for now.
    private static final List<String> blockStartsToKeepConditionally = new ArrayList<>();
    // Assume that string in remove are case-insensitive like ', ", etc.. Change string to pattern when a case-sensitive string to remove appears.
    private static final List<Pattern> removePatterns = new ArrayList<>();

    public static final String LIST = "list";

    // Special handle of <> blocks is not yet implemented.
    // - start pattern is "\\<(?<value>[^>^/]+)\\>"
    // - end pattern is "\\<\\/(?<value>[^>^/]+)\\>"
    // - selfclosing pattern is "\\<(?<value>[^>^/]+)\\/\\>"

    // Handle the single fakestart "{{" manually;
    static void addStartOfEnd(String start, boolean strip) {
        addStartOfEnd(start, "\\{\\{\\s*" + start, strip);
    }

    static void addStartOfEnd(String start, String startPatternString, boolean strip) {
        addStartOfEnd("}}", start, startPatternString, strip);
    }
    
    
    static void addStartOfEnd(String end, String start, String startPatternString, boolean strip) {
        addStartOfEnd(end, "\\}\\}", start, startPatternString, strip);
    }

    static void addStartOfEnd(String end, String endPatternString, String start, String startPatternString, boolean strip) {
        endOfStarts.put(start, end);
        if (!startEndAndRemovePatterns.containsKey(end)) {
            startEndAndRemovePatterns.put(end, Pattern.compile(endPatternString, Pattern.CASE_INSENSITIVE));
        }
        List<String> startsOfEnd = startsOfEnds.get(end);
        if (startsOfEnd == null) {
            startsOfEnd = new ArrayList<>();
            startsOfEnds.put(end, startsOfEnd);
        }
        startsOfEnd.add(start);
        startEndAndRemovePatterns.put(start, Pattern.compile(startPatternString, Pattern.CASE_INSENSITIVE));
        blockStarts.add(start);
        if (strip) stripStarts.add(start);
    }

    static {
        blockStartsToKeepConditionally.add("infobox");

        // add "item_style ... ;" "style ... ;" (end is ";" startpattern is item_style|stye[^;]*) AND simply remove ';' if there is no startstyle on the stack!
        removePatterns.add(Pattern.compile("''+"));  // 2 aposts means at least 2 aposts, qualifiers sometimes are apostrophed like ''(de facto)''
        removePatterns.add(Pattern.compile("\\<br\\s*\\>", Pattern.CASE_INSENSITIVE));
        removePatterns.add(Pattern.compile("\\<br\\s*\\/\\>", Pattern.CASE_INSENSITIVE));
        removePatterns.add(Pattern.compile("\\<p\\s*\\>", Pattern.CASE_INSENSITIVE));
        removePatterns.add(Pattern.compile("\\<p\\s*\\/\\>", Pattern.CASE_INSENSITIVE));
        removePatterns.add(Pattern.compile("&nbsp;", Pattern.CASE_INSENSITIVE));

        addStartOfEnd("infobox", false);
        addStartOfEnd("flagicon", false);
        addStartOfEnd("efn", "\\{\\{\\s*(efn\\||efn)", false);
        addStartOfEnd("cite web", false);
        addStartOfEnd("ref", false);
        addStartOfEnd("small", "\\{\\{small\\|", true);
        addStartOfEnd("center", "\\{\\{center\\|", true);
        // see Afhanistan in https://en.wikipedia.org/wiki/List_of_ISO_3166_country_codes?action=raw where UN member state appears in nowrap.
        // addStartOfEnd("nowrap", false);

        String listStartFormat = "\\{\\{%s";
        String[] listStarts = new String[] { "collapsible list", "unbulleted list", "hlist", "plainlist", "plain list", "ublist", "flatlist", "unbulleted list"};
        String listStartPatternString = new String();
        for (int i = 0; i < listStarts.length; ++i) {
            String s = String.format(listStartFormat, listStarts[i]);
            String sor = s + "\\||" + s;
            listStartPatternString += i == 0 ? sor : "|" + sor;
        }
        addStartOfEnd(LIST, listStartPatternString, true);
        // addStartOfEnd("-->", "\\-\\-\\>", "<!--", "\\<\\!\\-\\-", false);
        addStartOfEnd(">", "\\>", "<", "\\<", false);
        // The problem with '' is that it is not necessarily the end of ''See.
        // If we have ''See x ''y'' z'' then we want to remove xyz but we could do it only when at the last ''.
        // addStartOfEnd("''", "''", "''See ", "''See ", false);
    }

    private int lineNumber = 0;
    // counts the number
    int numberOfOPenBlocksToRemove = 0;
    private int lastOpenStartLineNumber = -1;
    private int lastOpenNoteStart = -1;
    // The number of blocks (open or closed) for blockStartsToKeepConditionally.
    // If the count is greater than zero (if it is present) when a new block appears then the block is removed.
    private Map<String, Integer> blocksToKeepConditionally = new TreeMap<>();
    private Set<String> blockStartsInCurrentLine = new TreeSet<>();
    private Set<String> blockEndsInCurrentLine = new TreeSet<>();

    private boolean debug = false;

    private void debug(String s) {
        if (debug) System.out.println(s);
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public int getNumberOfOpenBlocksToRemove() {
        return numberOfOPenBlocksToRemove;
    }

    public int getNumberOfOpenBlocks() {
        return stack.size();
    }

    public int getDepth(String s) {
        if (!blockStartsToKeepConditionally.contains(s)) return -1;
        int depth = 0;
        for (BlockStart limit : stack) {
            if (limit.getData().equals(s)) ++depth;
        }
        return depth;
    }

    public boolean inTheFirstAppearanceOf(String s) {
        // If the blockStart with count 2 start in the current line then return true.
        // The updated line ends at the start of this block.
        // Otherwise return if the first blockStart has count 1.
        for (BlockStart limit : stack) {
            // lineNumber already incremented!
            if (limit.getData() == s && limit.getConditionallyKeepCount() == 2 && limit.getLineNumber() == lineNumber - 1) {
                return true;
            }
        }
        for (BlockStart limit : stack) {
            if (limit.getData() == s) return limit.getConditionallyKeepCount() == 1;
        }
        return false;
    }

    public boolean blockStartsInCurrentLine(String s) {
        return blockStartsInCurrentLine.contains(s);
    }

    public boolean blockEndsInCurrentLine(String s) {
        return blockEndsInCurrentLine.contains(s);
    }

    public String update(String line) throws Exception {
        String os = line;

        Map<Integer, Interval> fakeStarts = new TreeMap<>();
        findAllSubstrings(line, "{{", fakeStarts);
    
        List<Interval> startEndIntervals = new ArrayList<>();
        for (String end : startsOfEnds.keySet()) {
            findAll(line, end, null, startEndIntervals);
        }
        for (String start : blockStarts) {
            findAll(line, start, fakeStarts, startEndIntervals);
        }
    
        // Add remaining fakeStarts to startEndIntervals.
        startEndIntervals.addAll(fakeStarts.values());
        
        // Intervals span the pattern of their data, the span (x, y) is for the
        // patternmatching "{{span|" the data is "span|" for example.
        Collections.sort(startEndIntervals);
        List<Interval> toRemove = new ArrayList<>();
        for (Pattern p : removePatterns) {
            Matcher matcher = p.matcher(line);
            while (matcher.find()) {
                toRemove.add(new Interval(matcher.start(), matcher.end()));
            }
        }

        blockStartsInCurrentLine.clear();
        blockEndsInCurrentLine.clear();
        for (Interval intvl : startEndIntervals) {
            // Assume that starts and ends are disjoint.
            // If intvl is a block end.
            debug(intvl + "");
            // Handle endBlocks first, those are with data in the keys of startsOfEnds.
            if (startsOfEnds.containsKey(intvl.getData())) {
                debug("END");
                if (stack.isEmpty()) {
                    // If we are in an open block to be removed then ignore this end.
                    System.out.println("Update found unbalanced block for >" + intvl + "< at line " + lineNumber + ":" + line);
                    System.out.println(intvl);
                    printStack();
                    ++lineNumber;
                    return "";
                }
				BlockStart bl = stack.pop();  // innermost block, pop start and don't push end
                blockEndsInCurrentLine.add(bl.getData());
                if (startsOfEnds.get(intvl.getData()).contains(bl.getData())) {
                    debug("    ENDs START");
                    // Only noteStarts can be stripped so we are done if bl is not a note.
                    if (!bl.getIsNote()) continue;
                    int count = bl.getConditionallyKeepCount();
                    // remove or strip if unconditionally keep (count is 0) or
                    // condition to remove stands (count > 1 for now).
                    if (count == 0 || count > 1) {  // ??? think this over!!!!!
                        boolean currentLine = lineNumber == bl.getLineNumber();
                        // clear lastOpenStart if it is this blockstart (lastOpenStart tells to remove everything
                        // after it in this line and subsequent lines until endblock appears, now we have the endblock).
                        if (currentLine && lastOpenNoteStart == bl.getPos()) {
                            lastOpenNoteStart = -1;
                        }
                        if (bl.isStrip()) {
                            // remove endBlock if it has to be stripped. The start has been stripped earlier when it was pushed.
                            toRemove.add(intvl);
                        } else {
                            int start = currentLine ? bl.getPos() : 0;
                            int end = intvl.getY();
                            toRemove.add(new Interval(start, end));
                            --numberOfOPenBlocksToRemove;
                        }
                    }
                } else {
                    debug("    DOESN'T END START");
                } 
            } else {
                // If we are in an open block to be removed then ignore this start.
                // if (getNumberOfOpenBlocksToRemove() != 0) continue;
                boolean isNoteStart = !fakeStarts.containsKey(intvl.getX());
                BlockStart bl = new BlockStart(lineNumber, intvl.getX(), isNoteStart);
                bl.setData(intvl.getData());
                blockStartsInCurrentLine.add(bl.getData());
                if (isNoteStart) {
                    boolean remove = true;
                    // if blockStart is to be conditionally kept then check if condition is true
                    // i.e. if this is its first appearance for now
                    if (blockStartsToKeepConditionally.contains(bl.getData())) {
                        Integer count = blocksToKeepConditionally.get(bl.getData());
                        if (count == null) count = 0;
                        ++count;
                        blocksToKeepConditionally.put(bl.getData(), count);
                        bl.setConditionallyKeepCount(count);
                        debug(  "  CONDKEEP " + ", " + bl);
                        remove = count != 1;
                    }
                    if (intvl.isStrip()) {
                        bl.setStrip(true);                
                        remove = false;
                        toRemove.add(intvl);
                    }

                    if (remove) {
                        // If not only strip then remember this notestart and remove until the end of line from here
                        // if this block doesn't get closed in this line.
                        if (lastOpenNoteStart == -1) {
                            lastOpenNoteStart = intvl.getX();
                            lastOpenStartLineNumber = lineNumber;
                        }
                        ++numberOfOPenBlocksToRemove;
                    }
                }
                debug("PUSH START " + bl);
                stack.push(bl);
            }
        }

        if (getNumberOfOpenBlocksToRemove() != 0) {
            if (lastOpenNoteStart != -1 && lastOpenStartLineNumber == lineNumber) {
                toRemove.add(new Interval(lastOpenNoteStart, line.length()));
            } else {
                ++lineNumber;
                return "";
            }
        }

        if (toRemove.isEmpty()) {
            ++lineNumber;
            return line.trim();
        }
        List<Interval> sorted = Interval.disjointUnion(toRemove);
        Collections.reverse(sorted);

        for (Interval intvl : sorted) {
            try {
                line = line.substring(0, intvl.getX()) + line.substring(intvl.getY());
            } catch (Throwable t) {
                System.out.println("Exception: " + t.getMessage());
                printStack();
                System.out.println("Interval " + intvl + ", lineNumber " + lineNumber + "\n" + line + "\n" + os);
                for (Interval i : sorted) {
                    System.out.println("    " + i);
                }
                System.exit(2);
            }
        }
        ++lineNumber;
        return line.trim();
    }

    public void printStack() {
        System.out.println("The stack " + stack.size());
        for (BlockStart b : stack) {
            System.out.println("  " + b);
        }
    }

    private void findAllSubstrings(String line, String s,  Map<Integer, Interval> found) {
        //debug("  FINDALLSUBS >" + s + "<");
        for (int i = 0; i <= line.length() - s.length(); i++) {
			if (line.substring(i, i + s.length()).equals(s)) {
                //debug("    found " + i + ", " + (i + s.length()));
                found.put(i, new Interval(i, i + s.length(), s));
                ++i;
            }
        }
    }

    private void findAll(String line, String s,  Map<Integer, Interval> fakeStarts, List<Interval> intervals) {
        //debug("FINDALL >" + s + "<");
        Pattern pattern = startEndAndRemovePatterns.get(s);
        if (pattern == null) {
            System.out.println("No pattern for " + s);
            System.exit(2);
        }
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
          // debug("  at " + matcher.start() + " " + line.substring(matcher.start(), matcher.end()));
          if (fakeStarts != null) {
            fakeStarts.remove(matcher.start());
          }
          intervals.add(new Interval(matcher.start(), matcher.end(), s, stripStarts.contains(s)));
        }
    }
}
