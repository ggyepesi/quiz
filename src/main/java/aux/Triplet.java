package aux;

public class Triplet<X, Y, Z>  extends Pair<X, Y> {
    private Z z;

    public Triplet(X x, Y y, Z z) {
        super(x, y);
        this.z = z;
    }

    public Z getZ() {
        return z;
    }
    public void setZ(Z z) {
        this.z = z;
    }
    public String toString() {
        return super.toString() + ", " + z;
    }
}
