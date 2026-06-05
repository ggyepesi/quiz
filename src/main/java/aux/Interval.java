package aux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public 
class Interval extends Pair<Integer, Integer> implements Comparable<Interval> {
    public static List<Interval> disjointUnion(List<Interval> intervals){
        Collections.sort(intervals);
        Interval prev = intervals.get(0);
        List<Interval> result = new ArrayList<>();
        for(int i = 0; i < intervals.size() ;i++){
            Interval next = intervals.get(i);
            if(next.getX() < prev.getY()) {
                if(next.getY() > prev.getY()) {
                    prev.setY(next.getY());
                }
            } else {
                result.add(prev);
                prev = next;
            }
        }
        result.add(prev);
        return result;
    }

    private String data;
    private boolean strip = false;

    public Interval(Integer x, Integer y)  {
        super(x, y);
        if (x < 0 || x > y) new Exception().printStackTrace();
    }

    public Interval(Integer x, Integer y, String data) {
        this(x, y);
        this.data = data;
    }
    
    public Interval(Integer x, Integer y, String data, boolean strip) {
        this(x, y, data);
        this.strip = strip;
    }

   public int compareTo(Interval i) {
       return getX() - i.getX();
    }

    public boolean equals(Object o) {
        if (o == null || !(o instanceof  Interval))
            return false;
        if(this == o)
            return true;
        Interval that = (Interval)o;
        return getX() == that.getX() && getY() == that.getY();
     }

     @Override
     public int hashCode() {
         return Objects.hash(getX(), getY());
     }

    public String getData() {
        return data;
    }
    public void setData(String data) {
        this.data = data;
    }
    public boolean isStrip() {
        return strip;
    }
    public void setStrip(boolean strip) {
        this.strip = strip;
    }
    public String toString() {
        return "Intvl " + getX() + ", " + getY() + ", [" + data + "], strip " + strip;
    }
}

