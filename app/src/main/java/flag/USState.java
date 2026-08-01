package flag;

import aux.FlexibleDate;

/**
 * A US state — a {@link State} specialization that carries the fields meaningful only
 * to the fifty US states (their {@code admissionDate}), rather than parking them on the
 * general {@link State} class where they are null for every country.
 *
 * <p>Experimental first subclass: its purpose is to exercise how a manually modeled
 * subclass flows through the pipelines (reflection field set, snapshot save/load,
 * transform typing, rendering, web) before we make subclass-per-group a first-class
 * transform act. Its {@code typeName()} is "USState" (the simple class name).
 */
public class USState extends State {

    private FlexibleDate admissionDate;

    public USState(String name) {
        super(name);
    }

    public FlexibleDate getAdmissionDate() {
        return admissionDate;
    }

    public void setAdmissionDate(FlexibleDate admissionDate) {
        this.admissionDate = admissionDate;
    }
}
