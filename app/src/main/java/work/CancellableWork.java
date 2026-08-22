package work;

/** A capability that can have work in flight, and can be told to stop it. */
public interface CancellableWork {
    void cancelActiveWork();
}
