package de.intranda.goobi.plugins.hotfolder.nli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.goobi.production.flow.jobs.AbstractGoobiJob;
import org.goobi.production.importer.ImportObject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;

import de.intranda.goobi.plugins.hotfolder.nli.model.NLIHotfolderImport;
import de.intranda.goobi.plugins.hotfolder.nli.model.config.HotfolderPluginConfig;
import de.intranda.goobi.plugins.hotfolder.nli.model.exceptions.ImportException;
import de.intranda.goobi.plugins.hotfolder.nli.model.hotfolder.HotfolderFolder;
import de.intranda.goobi.plugins.hotfolder.nli.model.hotfolder.HotfolderParser;
import de.intranda.goobi.plugins.hotfolder.nli.model.log.GUIImportResult;
import de.intranda.goobi.plugins.hotfolder.nli.model.log.QuartzJobLog;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class HotfolderNLIQuartzJob extends AbstractGoobiJob {
    private static final String PLUGIN_NAME = "intranda_administration_hotfolder_nli";
    private static StorageProviderInterface storageProvider = StorageProvider.getInstance();

    private static final String RESULTS_JSON_FILENAME = "lastRunResults.json";

    private HotfolderPluginConfig config = new HotfolderPluginConfig(PLUGIN_NAME);

    private HotfolderParser hotfolderParser = new HotfolderParser();

    // only used to test QuartzJobLog
    private static int counter = 0;

    @Override
    public String getJobName() {
        return "HotfolderNLIQuartzJob";
    }

    /**
     * Quartz-Job implementation
     */
    @Override
    public void execute() {
        // assure that allowedNumberOfLogs is at least 1

        log.debug("useTimeDifference = " + config.isUseTimeDifference());
        if (config.isUseTimeDifference()) {
            log.debug("allowedTimeDifference = " + config.getAllowedTimeDifference());
        } else {
            log.debug("allowedNumberOfLogs = " + config.getAllowedNumberOfLogs());
        }

        if (!storageProvider.isFileExists(config.getHotfolderPath())) {
            log.info("NLI hotfolder is not present: " + config.getHotfolderPath());
            return;
        }

        Path pauseFile = config.getHotfolderPath().resolve("hotfolder_pause.lock");
        if (storageProvider.isFileExists(pauseFile)) {
            log.info("NLI hotfolder is paused - not running");
            return;
        }

        Path lockFile = config.getHotfolderPath().resolve("hotfolder_running.lock");
        if (storageProvider.isFileExists(lockFile)) {
            log.info("NLI hotfolder is already running - not running a second time in parallel");
            return;
        }

        // prepare QuartzJobLog instance for recording QuartzJob errors and periods where there is no file to upload
        QuartzJobLog quartzJobLog = QuartzJobLog.getInstance(config.getHotfolderPath());
        List<GUIImportResult> guiResults = Collections.emptyList();
        try {
            // set lock
            Files.createFile(lockFile);
            log.info("NLI hotfolder: Starting import run");

            List<HotfolderFolder> importFolders = this.hotfolderParser.getImportFolders(config.getHotfolderPath(), config);

            // create an ImportObject instance for every folder in the importFolders
            List<ImportObject> imports = createProcesses(importFolders);
            log.info("NLI hotfolder: Created processes. " + imports.size() + "import objects were created");

            guiResults = imports.stream()
                    .map(GUIImportResult::new)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error running NLI hotfolder: {}", e);
            // record the exception
            quartzJobLog.addErrorEntry(e.getMessage());
        } catch (Throwable e) {
            log.error("Unexpected error running NLI hotfolder: {}", e);
            // record the unexpected error
            quartzJobLog.addErrorEntry(e.getMessage());
        } finally {

            if (guiResults.size() > 0) {
                // ending an existing period where no file is to upload, no harm if no period has been started yet
                quartzJobLog.markPeriodEnd();

                int numberSetting = config.isUseTimeDifference() ? config.getAllowedTimeDifference() : config.getAllowedNumberOfLogs();
                updateRunsLog(config.getHotfolderPath(), guiResults, numberSetting);
            } else {
                // starting a new period where no file is to upload, no harm if the period has already been started earlier
                quartzJobLog.markPeriodStart();
                log.debug("guiResults is empty, skipping...");
            }

            log.info("NLI hotfolder: Done with import run. Deleting lockFile");
            try {
                if (storageProvider.isFileExists(lockFile)) {
                    storageProvider.deleteFile(lockFile);
                }
            } catch (IOException e) {
                log.error("Error deleting NLI hotfolder lock file: {}", e);
                // record the IOException
                quartzJobLog.addErrorEntry("Error deleting NLI hotfolder lock file: " + e.getMessage());
            }
        }
    }

    public List<ImportObject> createProcesses(List<HotfolderFolder> importFolders) throws IOException {
        List<ImportObject> imports = new ArrayList<>();
        NLIHotfolderImport importer = new NLIHotfolderImport(config, this.storageProvider, ConfigurationHelper.getInstance().getTemporaryFolder(),
                ConfigOpac.getInstance());
        for (HotfolderFolder hff : importFolders) {

            try {
                imports.addAll(importer.createProcessesFromHotfolder(hff));
            } catch (NullPointerException | IllegalStateException e) {
                log.error("NLI hotfolder - unexpected error " + e.toString() + " when processing import folder " + hff.getProjectFolder(), e);
            } catch (ImportException e) {
                log.error("NLI hotfolder - Error  when processing import folder " + hff.getProjectFolder() + ". Reason: " + e.toString());
            }
        }

        return imports;
    }

    // ======= private methods ======= //

    private void updateRunsLog(Path hotfolderPath, List<GUIImportResult> guiResults, int numberSetting) {
        // write result to a json file located at the hotfolderPath
        ObjectMapper om = new ObjectMapper();
        log.info("NLI hotfolder: Writing import results to " + hotfolderPath);

        Path resultsJsonPath = hotfolderPath.resolve(RESULTS_JSON_FILENAME);

        String reducedLastResult = "";
        try {
            reducedLastResult = getReducedPreviousRunInfos(resultsJsonPath, numberSetting);
        } catch (IOException e) {
            log.error("Error trying to update the log file: {}", e);
            return;
        }

        // TODO: How to use StorageProviderInterface to replace Files in the following case?
        try (OutputStream out = Files.newOutputStream(resultsJsonPath, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.CREATE)) {

            out.write("[".getBytes());
            // new results come first
            out.write(om.writeValueAsBytes(guiResults));
            // append old results
            out.write(reducedLastResult.getBytes());
        } catch (IOException e) {
            log.error("Error trying to update the log file: {}", e);
        }
    }

    private String getReducedPreviousRunInfos(Path resultsJsonPath, int numberSetting) throws IOException {
        if (!storageProvider.isFileExists(resultsJsonPath)) {
            Files.createFile(resultsJsonPath);
            return "]";
        }

        String lastResults = "";
        try (InputStream inputStream = storageProvider.newInputStream(resultsJsonPath)) {
            lastResults = new String(inputStream.readAllBytes());
        }

        if (StringUtils.isBlank(lastResults)) {
            // the log file is still empty
            log.debug(resultsJsonPath + " is blank");
            return "]";
        }

        return config.isUseTimeDifference() ? getReducedPreviousRunInfosGivenTimeDifference(lastResults, numberSetting)
                : getReducedPreviousRunInfosGivenNumberLimit(lastResults, numberSetting);
    }

    private String getReducedPreviousRunInfosGivenNumberLimit(String lastResults, int allowedNumberOfLogs) {

        if (allowedNumberOfLogs <= 1) {
            return "]";
        }

        // otherwise, only the first allowedNumberOfLogs - 1 logs from lastResults will be kept
        lastResults = ",\n" + lastResults.substring(1);

        // use StringBuilder and count the number of [
        StringBuilder sb = new StringBuilder();

        int numberOfLogs = 0;
        boolean startingNewLog = false;
        for (String s : lastResults.split("")) {
            if ("[".equals(s)) {
                numberOfLogs += 1;
                startingNewLog = true;
            }

            sb.append(s);

            if (startingNewLog && "]".equals(s)) {
                startingNewLog = false;
                if (numberOfLogs == allowedNumberOfLogs - 1) {
                    // time to cut out the tail
                    sb.append("]");
                    break;
                }
            }
        }

        return sb.toString();
    }

    private String getReducedPreviousRunInfosGivenTimeDifference(String lastResults, int allowedTimeDifference) {
        // lastResults has form [[{},{}],\n[{},{}],\n[{},{}]]
        // get rid of the first [ and the last ]
        String results = lastResults.substring(lastResults.indexOf("[") + 1, lastResults.lastIndexOf("]"));
        // split results into an array of arrays formed like [GUIImportResult, GUIImportResult, GUIImportResult, ...],
        // where each array represents a log entry of one previous run
        String[] resultsArray = results.split(",\\n");
        LocalDateTime now = LocalDateTime.now();

        StringBuilder sb = new StringBuilder(",\n");
        for (String result : resultsArray) {
            if (!checkTimeStamp(result, now, allowedTimeDifference)) {
                log.debug("Allowed time difference reached, discarding the following logs.");
                break;
            }

            // time stamp check passed
            sb.append(result);
            sb.append(",\n");
        }

        // remove the last ",\n"
        int sbLength = sb.length();
        sb.delete(sbLength - 2, sbLength);
        sb.append("]");

        return sb.toString();
    }

    private boolean checkTimeStamp(String result, LocalDateTime now, int allowedTimeDifference) {
        DateTimeFormatter formatter = GUIImportResult.getFormatter();
        Gson gson = new Gson();
        try {
            GUIImportResult[] importResults = gson.fromJson(result, GUIImportResult[].class);
            for (GUIImportResult importResult : importResults) {
                String timestamp = importResult.getTimestamp();
                LocalDateTime dateTime = LocalDateTime.parse(timestamp, formatter);
                Duration duration = Duration.between(dateTime, now);
                if (duration.toHours() < allowedTimeDifference) {
                    return true;
                }
            }
            return false;

        } catch (DateTimeParseException e) {
            log.debug("DateTimeParseException caught during the time stamp check.");
            return false;

        } catch (Exception ex) {
            log.debug("Exception caught during the time stamp check, probably an MalformedJsonException.");
            return false;
        }
    }

}
