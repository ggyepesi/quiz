/**
 * The controls both workbenches share.
 *
 * <p>ModelBuilder and TransformApp ask the same questions about where a field's data comes
 * from, and they had two ways of sharing an answer: duplicate it, or import the other app's
 * user interface. The choose-a-source dialog took the first road and survived being pointed
 * at three times — a duplication with nowhere to live does not get fixed. The pickers, the
 * QID links and the identity chip took the second, so the transform workbench depended on
 * ModelBuilder's UI package for seven types.
 *
 * <p>Both apps depend on this package instead, and it depends on neither of them. What
 * belongs here is a control whose behaviour is the same wherever it is used —
 * {@link workbench.AdditionalSourcePicker} asks which structure to sample and hands back the
 * answer; what each app then WRITES (a model mapping, a curation recipe) stays with the app,
 * because that is the part they genuinely do not share.
 *
 * <p>{@code WorkbenchSharingTest} keeps it that way: neither app's UI may import the other's.
 */
package workbench;
