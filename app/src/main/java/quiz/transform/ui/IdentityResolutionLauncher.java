package quiz.transform.ui;

import objectview.Viewable;

import java.util.List;

/**
 * Reusable entry point for resolving any explicit instance scope: rendered results,
 * validation gaps, groups, filters, or a future manual selection.
 */
@FunctionalInterface
public interface IdentityResolutionLauncher {
    void resolve(List<Viewable> instances, String scopeLabel);
}
