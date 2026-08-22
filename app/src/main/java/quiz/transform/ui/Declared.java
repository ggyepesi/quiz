package quiz.transform.ui;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A fact the BACKING states. Its default, if it has one, means "I have nothing to say"
 * — so a wrapper that does not forward it answers on its base's behalf, and the base's
 * declaration disappears with no compile error and no failing test.
 *
 * <p>That has happened twice: a model's category recipe went silent the moment a
 * PROJECT-derived class was layered over the domain (39f6f2a), and its declared fallback
 * source went the same way the same month. {@link DelegatingDomainModel} forwards every
 * method marked this way, so the safe thing is the automatic thing, and
 * {@code DomainContractTest} fails if one is added without being forwarded.
 *
 * @see Derived
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Declared { }
