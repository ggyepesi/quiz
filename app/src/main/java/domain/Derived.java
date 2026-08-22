package domain;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Computed from what the backing declares, and therefore NOT forwarded by a wrapper: the
 * whole point is that it recomputes over whatever that wrapper itself declares. Forwarding
 * {@code isSubclassOf} to a base would answer using the base's {@code baseType} and quietly
 * ignore the wrapper's own hierarchy.
 *
 * <p>So the two kinds carry opposite obligations, and nothing in the interface used to say
 * which was which. Both failure modes are silent, which is why every method is annotated
 * one way or the other and {@code DomainContractTest} refuses a method that is neither.
 *
 * @see Declared
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Derived { }
