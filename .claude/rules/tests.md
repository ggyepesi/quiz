---
description: What a test in this project is for, and how it reads
paths:
  - "app/src/test/**/*.java"
  - "objectview/src/test/**/*.java"
---

# Tests

**A rule that matters gets a forcing test.** A guard test that fails on new violations makes the
rule self-enforcing and turns "remaining work" into a shrinking allowlist —
`NameBasedRoleGuardTest` is the model. Prefer this over restating the rule in a comment.

**A test states the behaviour, not the method.** Names are sentences about what is true:
`aReadTimeoutIsNotReportedAsAnHttpOutcome`, `anUnlabelledEntityExampleIsNotShownTwice`,
`aMediaCellIsLaidOutAsAThumbnail`. The class javadoc says what went wrong and why the behaviour
is what it is, so the next reader knows what the test is defending.

**Pin the bug, not just the fix.** When something reached the user, the test reproduces the
exact reported shape — the 50-QID batch that timed out, the connection whose status still read
200 — and, where the fix hinges on a distinction, also asserts the *other* side of it still
behaves (a real 429 keeps its status and `Retry-After`; an unexpected status stays FATAL).

**Test the seam, not the network.** Extract the decision into a small static function
(`transportFailure`, `worthRetryingUnchanged`, `display`) and test that. Where a client must be
exercised, override the one protected batch method; `ON_PAINT` media never loads, so rendering
tests stay pure.

**A rendering rule is tested in every layout it claims to cover.** Teaching one renderer and
testing only that renderer is how a value came to show as an image in a table and as plain text
in a card, one view-mode toggle apart.

Run the reactor, never `-pl app` alone:

```
mvn -o -pl app -am test
mvn -o -pl app -am test -Dtest='SomeTest' -Dsurefire.failIfNoSpecifiedTests=false
```
