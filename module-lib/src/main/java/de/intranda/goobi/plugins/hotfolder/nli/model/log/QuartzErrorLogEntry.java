package de.intranda.goobi.plugins.hotfolder.nli.model.log;

import java.io.PrintWriter;
import java.io.StringWriter;

import org.apache.commons.lang3.StringUtils;

import lombok.Getter;

/**
 * A single, enriched entry of the quartz error log: when it happened, in which hotfolder, what was being processed (context) and the full
 * exception behind it.
 */
@Getter
public class QuartzErrorLogEntry {

    private final String time;
    private final String hotfolder;
    private final String context;
    private final String errorType;
    private final String message;
    private final String stackTrace;

    public QuartzErrorLogEntry(String time, String hotfolder, String context, Throwable error) {
        this.time = time;
        this.hotfolder = hotfolder;
        this.context = context;
        this.errorType = error == null ? null : error.getClass().getName();
        this.message = error == null ? null : error.getMessage();
        this.stackTrace = error == null ? null : stackTraceOf(error);
    }

    private static String stackTraceOf(Throwable error) {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * A single-line, never-null summary of this entry, safe to use as a CSV field (no commas, no line breaks).
     */
    public String toCsvSummary() {
        String errorText = errorType == null ? "unknown error" : errorType + (StringUtils.isNotBlank(message) ? ": " + message : "");
        String summary = StringUtils.isNotBlank(context) ? context + " - " + errorText : errorText;
        return summary.replace("\r\n", " ").replace("\n", " ").replace(",", ";");
    }
}
