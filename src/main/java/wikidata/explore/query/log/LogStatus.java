package wikidata.explore.query.log;

import wikidata.explore.query.core.QueryStatus;

public enum LogStatus {

    PENDING(""),
    RUNNING("Running..."),
    OK("Done"),
    FAILED("Failed"),
    CANCELLED("Cancelled");

    private final String defaultSummary;

    LogStatus(String defaultSummary) {
        this.defaultSummary = defaultSummary;
    }

    public String defaultSummary() {
        return defaultSummary;
    }

    public boolean isTerminal() {
        return this == OK || this == FAILED || this == CANCELLED;
    }

    public static LogStatus from(QueryStatus status) {
        if (status == null) {
            return OK;
        }

        return switch (status) {
            case RUNNING -> RUNNING;
            case OK -> OK;
            case FAILED -> FAILED;
            case CANCELLED -> CANCELLED;
        };
    }
}
