package aux;

public class BlockStart {
    private int lineNumber;
    private int pos;
    private boolean isNote;
    private int conditionallyKeepCount = 0;
    private boolean strip = false;
    private String data = "";
    
    public BlockStart(int lineNumber, int pos, boolean isNote) {
        this.lineNumber = lineNumber;
        this.pos = pos;
        this.isNote = isNote;
    }

    public int getLineNumber() {
        return lineNumber;
    }
    public int getPos() {
        return pos;
    }
    public boolean getIsNote() {
        return isNote;
    }
    public String getData() {
        return data;
    }
    public void setData(String data) {
        this.data = data;
    }
    public int getConditionallyKeepCount() {
        return conditionallyKeepCount;
    }
    public void setConditionallyKeepCount(int conditionallyKeepCount) {
        this.conditionallyKeepCount = conditionallyKeepCount;
    }
    public boolean isStrip() {
        return strip;
    }
    public void setStrip(boolean strip) {
        this.strip = strip;
    }
    
    public String toString() {
        return "line " + lineNumber + ", pos " + pos + ", " + data + ", " +
               (isNote ? "noteStart" : "blockStart") + ", strip " + strip + ", ck " + conditionallyKeepCount;
    }
}
