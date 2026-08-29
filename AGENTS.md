# Agent guidance

Before planning, reviewing, or changing this repository, read `CLAUDE.md` completely. It is
the canonical project-wide engineering guidance; the path-scoped rules in `.claude/rules/`
add detail for the files they cover.

In particular, apply the architecture directives in `CLAUDE.md` before implementation:

1. Survey existing code, tests, documentation, and analogous UI/workflows before introducing
   a class, mechanism, or rendering path.
2. Find the current owner of the concept. Extend or factor that construct instead of creating
   a second derivation or a local exception.
3. Reuse a mechanism only when it covers all consumers; otherwise improve the shared
   mechanism first.
4. Keep one source of truth and one discovery path for each fact or decision.
5. Make user-visible actions explicit: selection or inspection must not silently mutate the
   model or start expensive work.
6. Encode important rules and reported failures as forcing tests.

Do not restate the full directives here. Update `CLAUDE.md` when a new project-wide agreement
is made, and update a path-scoped `.claude/rules/*.md` file when it applies only to that area.
