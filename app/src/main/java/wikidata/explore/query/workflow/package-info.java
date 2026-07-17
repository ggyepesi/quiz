/**
 * Query execution workflow: {@code QueryWorkflow} runs a {@code Query} against a
 * {@code QueryContext}, feeding steps/results to a sink and the log recorder.
 * The unit {@code query.swing} schedules off the EDT.
 */
package wikidata.explore.query.workflow;
