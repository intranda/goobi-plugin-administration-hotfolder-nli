package de.intranda.goobi.plugins.hotfolder.nli.model;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class QuartzJobLog {
    private static QuartzJobLog logInstance;
    private static final DateTimeFormatter formatter = GUIImportResult.getFormatter();

    private static final String NO_FILES_PERIOD_LOG_FILE = "noFilesPeriods.csv";
    private static final String QUARTZ_ERROR_LOG_FILE = "quartzErrorLog.csv";

    private Map<LocalDateTime, String> quartzErrorsMap;
    private Map<LocalDateTime, LocalDateTime> periodsMap;
    private LocalDateTime periodStart;
    private LocalDateTime periodEnd;
    private Path hotfolderPath;
    @Getter
    private Path errorsFilePath;
    @Getter
    private Path periodsFilePath;

    private QuartzJobLog(Path hotfolderPath) {
        log.debug("initializing logInstance");
        this.hotfolderPath = hotfolderPath;
        this.errorsFilePath = this.hotfolderPath.resolve(QUARTZ_ERROR_LOG_FILE);
        this.periodsFilePath = this.hotfolderPath.resolve(NO_FILES_PERIOD_LOG_FILE);
        initializeQuartzErrorsMap();
        initializePeriodsMap();
    }

    public static QuartzJobLog getInstance(Path hotfolderPath) {
        if (logInstance == null) {
            logInstance = new QuartzJobLog(hotfolderPath);
        }
        return logInstance;
    }

    /* methods for handling quartz errors */

    public void addErrorEntry(String errorMessage) {
        LocalDateTime now = LocalDateTime.now();
        quartzErrorsMap.put(now, errorMessage);
    }

    public void generateQuartzErrorsLogFile() {
        String content = generateQuartzErrorsContent();
        generateFile(errorsFilePath, content);
    }

    private String generateQuartzErrorsContent() {
        StringBuilder sb = new StringBuilder("TIME,ERROR\n");
        for (Entry<LocalDateTime, String> entry : quartzErrorsMap.entrySet()) {
            String time = entry.getKey().format(formatter);
            String error = entry.getValue();
            sb.append(time);
            sb.append(",");
            sb.append(error);
            sb.append("\n");
        }

        return sb.toString();
    }

    /* methods for handling no files periods */

    public void markPeriodStart() {
        if (periodStart != null) {
            // period start was already set to an earlier point, ignore it here
            return;
        }

        periodStart = LocalDateTime.now();
        // reset periodEnd
        periodEnd = null;
    }

    public void markPeriodEnd() {
        if (periodStart == null) {
            // no period end should be marked when period start is still open
            return;
        }

        periodEnd = LocalDateTime.now();
        // create an entry of this period
        periodsMap.put(periodStart, periodEnd);
        // reset periodStart
        periodStart = null;
    }

    public void generatePeriodsLogFile() {
        log.debug("generating periods log file");
        String content = generatePeriodsContent();
        generateFile(periodsFilePath, content);
    }

    private String generatePeriodsContent() {
        StringBuilder sb = new StringBuilder("PERIOD_START,PERIOD_END\n");
        for (Entry<LocalDateTime, LocalDateTime> entry : periodsMap.entrySet()) {
            String start = entry.getKey().format(formatter);
            String end = entry.getValue().format(formatter);
            sb.append(start);
            sb.append(",");
            sb.append(end);
            sb.append("\n");
        }

        return sb.toString();
    }

    /* general methods */

    private void generateFile(Path filePath, String content) {
        // TODO: How to use StorageProviderInterface to replace Files in the following case?
        try (OutputStream out = Files.newOutputStream(filePath, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.CREATE)) {

            out.write(content.getBytes());

        } catch (IOException e) {
            log.error("Error trying to save the file: {}", e);
        }
    }

    private void initializeQuartzErrorsMap() {
        quartzErrorsMap = new TreeMap<>(Collections.reverseOrder());
    }

    private void initializePeriodsMap() {
        periodsMap = new TreeMap<>(Collections.reverseOrder());
    }

    public void deleteAllRecords() {
        initializeQuartzErrorsMap();
        initializePeriodsMap();
    }

}
