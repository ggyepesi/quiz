/**
 * Bridges queries to the Swing UI.
 *
 * <p>{@code SwingQueryRunner} runs a {@code Query} off the EDT (via a
 * {@code QueryWorkflow}), wires a button to a query supplier + result sink +
 * error handler, and streams logs. {@code QueryObjectResultPanel} /
 * {@code QueryTableResultPanel} render results; {@code WorkflowLogWindow} shows
 * the live query log. UI panels run queries through here and never touch
 * endpoints directly.
 */
package wikidata.explore.query.swing;
