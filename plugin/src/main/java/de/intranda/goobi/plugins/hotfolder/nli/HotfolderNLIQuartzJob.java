package de.intranda.goobi.plugins.hotfolder.nli;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.goobi.beans.Step;
import org.goobi.production.enums.ImportReturnValue;
import org.goobi.production.flow.helper.JobCreation;
import org.goobi.production.flow.jobs.AbstractGoobiJob;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.intranda.goobi.plugins.hotfolder.nli.model.GUIImportResult;
import de.intranda.goobi.plugins.hotfolder.nli.model.HotfolderFolder;
import de.intranda.goobi.plugins.hotfolder.nli.model.NLIExcelImport;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.HelperSchritte;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class HotfolderNLIQuartzJob extends AbstractGoobiJob {
    private static String title = "intranda_administration_hotfolder_nli";
    private static StorageProviderInterface storageProvider = StorageProvider.getInstance();

    private static final String RESULTS_JSON_FILENAME = "lastRunResults.json";

    //Why is this a global property? Should it not be recreated for each HotfolderFolder?
    private NLIExcelImport excelImport = null;

    @Override
    public String getJobName() {
        return "HotfolderNLIQuartzJob";
    }

    /**
     * Quartz-Job implementation
     */
    @Override
    public void execute() {
        XMLConfiguration config = ConfigPlugins.getPluginConfig(title);
        Path hotfolderPath = Paths.get(config.getString("hotfolderPath"));
        // assure that allowedNumberOfLogs is at least 1
        int allowedNumberOfLogs = Math.max(config.getInt("allowedNumberOfLogs", 1), 1);

        if (!storageProvider.isFileExists(hotfolderPath)) {
            log.info("NLI hotfolder is not present: " + hotfolderPath);
            return;
        }

        Path pauseFile = hotfolderPath.resolve("hotfolder_pause.lock");
        if (storageProvider.isFileExists(pauseFile)) {
            log.info("NLI hotfolder is paused - not running");
            return;
        }

        Path lockFile = hotfolderPath.resolve("hotfolder_running.lock");
        if (storageProvider.isFileExists(lockFile)) {
            log.info("NLI hotfolder is already running - not running a second time in parallel");
            return;
        }

        try {
            // set lock
            storageProvider.createFile(lockFile);
            log.info("NLI hotfolder: Starting import run");

            List<HotfolderFolder> importFolders = getImportFolders(hotfolderPath);

            // create an ImportObject instance for every folder in the importFolders
            List<ImportObject> imports = createProcesses(importFolders);
            log.info("NLI hotfolder: Created processes. " + imports.size() + "import objects were created");

            List<GUIImportResult> guiResults = imports.stream()
                    .map(GUIImportResult::new)
                    .collect(Collectors.toList());

            // write result to a json file located at the hotfolderPath
            ObjectMapper om = new ObjectMapper();
            log.info("NLI hotfolder: Writing import results to " + hotfolderPath);

            Path resultsJsonPath = hotfolderPath.resolve(RESULTS_JSON_FILENAME);

            String reducedLastResult = getReducedPreviousRunInfos(resultsJsonPath, allowedNumberOfLogs);
            log.debug("reducedLastResult = " + reducedLastResult);

            // TODO: How to use StorageProviderInterface to replace Files in the following case?
            try (OutputStream out = Files.newOutputStream(resultsJsonPath, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.CREATE)) {
                out.write("[".getBytes());
                // new results come first
                out.write(om.writeValueAsBytes(guiResults));
                // append old results
                out.write(reducedLastResult.getBytes());
            }

        } catch (Exception e) {
            log.error("Error running NLI hotfolder: {}", e);
        } catch (Throwable e) {
            log.error("Unexpected error running NLI hotfolder: {}", e);
        } finally {
            log.info("NLI hotfolder: Done with import run. Deleting lockFile");
            try {
                if (storageProvider.isFileExists(lockFile)) {
                    storageProvider.deleteFile(lockFile);
                }
            } catch (IOException e) {
                log.error("Error deleting NLI hotfolder lock file: {}", e);
            }
        }

    }

    /**
     * returns true for the following cases: 1). 0 < startTime <= currentHour < endTime 2). 0 < endTime <= startTime <= currentHour 3). 0 <
     * currentHour < endTime <= startTime 4). endTime <= 0 < startTime <= currentHour 5). startTime <= 0 < currentHour < endTime 6). startTime <= 0 &&
     * endTime <= 0
     * 
     * @param currentHour
     * @param startTime
     * @param endTime
     * @return
     */
    boolean shouldRunAtTime(int currentHour, Integer startTime, Integer endTime) {
        if (startTime > 0 && endTime > 0) {
            if (startTime < endTime) {
                return currentHour >= startTime && currentHour < endTime;
            } else {
                return currentHour >= startTime || currentHour < endTime;
            }
        } else if (startTime > 0) {
            return currentHour >= startTime;
        } else if (endTime > 0) {
            return currentHour < endTime;
        } else {
            return true;
        }
    }

    // ======= private methods ======= //

    private List<HotfolderFolder> getImportFolders(Path hotfolderPath) throws IOException {
        List<HotfolderFolder> importFolders = traverseHotfolder(hotfolderPath);
        log.info("NLI hotfolder: Traversed import folders. Found " + importFolders.size() + " folders");

        // check schedule to determine whether templates should be ignored or not
        List<String> ignoredTemplates = new ArrayList<>();
        importFolders = importFolders.stream().filter(folder -> {
            SubnodeConfiguration templateConfig = NLIExcelImport.getTemplateConfig(folder.getTemplateName());
            Integer startTime = templateConfig.getInt("schedule/start", 0);
            Integer endTime = templateConfig.getInt("schedule/end", 0);
            int currentHour = LocalDateTime.now().getHour();
            boolean run = shouldRunAtTime(currentHour, startTime, endTime);
            if (!run) {
                ignoredTemplates.add(folder.getTemplateName());
            }
            return run;
        }).collect(Collectors.toList());

        // report all ignored templates
        ignoredTemplates.stream()
                .distinct()
                .forEach(template -> log.info("NLI hotfolder: Ignore folders for template {} due to schedule configuration", template));

        return importFolders;
    }

    /**
     * Traverses the hotfolder and finds folders that have not been modified for 30 minutes. The folder structure looks like this: <br>
     * <br>
     * hotfolder/project_name/template_name/barcode <br>
     * <br>
     * <br>
     * hotfolder/template_name/project_name/barcode <br>
     * <br>
     * The barcode folders are the ones that are returned by this method.
     * 
     * @param hotfolderPath the path of the hotfolder
     * @return folders that have not been modified for 30 minutes
     * @throws IOException
     */
    private List<HotfolderFolder> traverseHotfolder(Path hotfolderPath) throws IOException {
        List<HotfolderFolder> stableBarcodeFolders = new ArrayList<>();
        // TODO: What is a proper candidate in StorageProviderInstance for replacing Files::newDirectoryStream?
        try (DirectoryStream<Path> templatesDirStream = Files.newDirectoryStream(hotfolderPath)) {
            for (Path templatePath : templatesDirStream) {
                if (storageProvider.isDirectory(templatePath)) {
                    try (DirectoryStream<Path> projectsDirStream = Files.newDirectoryStream(templatePath)) {
                        for (Path projectPath : projectsDirStream) {
                            if (storageProvider.isDirectory(projectPath)) {
                                stableBarcodeFolders.add(new HotfolderFolder(projectPath, templatePath.getFileName().toString()));
                            }
                        }
                    }
                }
            }
        }
        return stableBarcodeFolders;
    }

    private List<ImportObject> createProcesses(List<HotfolderFolder> importFolders) throws IOException {
        List<ImportObject> imports = new ArrayList<>();
        for (HotfolderFolder hff : importFolders) {
            try {
                File importFile = hff.getImportFile();
                if (importFile == null) {
                    List<Path> processFolders = hff.getCurrentProcessFolders();
                    log.trace("importFile: {}, processFolders.size(): {}", importFile, processFolders.size());
                    continue;
                }

                //otherwise:
                log.info("NLI hotfolder - importing: " + importFile);

                // prepare the NLIExcelImport object
                if (excelImport == null) {
                    excelImport = new NLIExcelImport(hff);
                }
                excelImport.setWorkflowTitle(hff.getTemplateName());

                // generate the list of all records
                List<Record> records = getAllRecords(imports, importFile);
                if (records == null) {
                    // some exceptions occurred, return immediately
                    return imports;
                }

                // add all ImportObjects regarding the current HotfolderFolder to the list
                addImportObjectsRegardingHotfolderFolder(imports, records, importFile, hff);

            } catch (IOException e) {
                log.info("NLI hotfolder - error: " + e.getMessage());
                throw e;
            } catch (NullPointerException | IllegalStateException e) {
                log.error("NLI hotfolder - unexpected error " + e.toString() + " when processing import folder " + hff.getProjectFolder(), e);
            }
        }

        return imports;
    }

    private List<Record> getAllRecords(List<ImportObject> imports, File importFile) {
        List<Record> records;
        try {
            // records will not be null as long as no exception is thrown
            records = excelImport.generateRecordsFromFile(importFile);
        } catch (IOException e) {
            ImportObject io = new ImportObject();
            io.setImportFileName(importFile.getAbsolutePath());
            io.setErrorMessage("Could not read import file");
            imports.add(io);
            return null;
        }

        return records;
    }

    private void addImportObjectsRegardingHotfolderFolder(List<ImportObject> imports, List<Record> records, File importFile, HotfolderFolder hff) {
        int lineNumber = 1;
        String importFilePath = importFile.toString();
        for (Record record : records) {
            lineNumber++;
            ImportObject io = prepareImportObject(importFilePath, lineNumber, record, hff);
            if (io != null) {
                imports.add(io);
            }
        }
    }

    private ImportObject prepareImportObject(String importFilePath, int lineNumber, Record record, HotfolderFolder hff) {
        ImportObject io = excelImport.generateFile(importFilePath, lineNumber, record, hff);
        if (io == null) {
            return null;
        }

        if (io.getImportReturnValue() == ImportReturnValue.ExportFinished) {
            //create new process
            org.goobi.beans.Process template = ProcessManager.getProcessByExactTitle(hff.getTemplateName());
            org.goobi.beans.Process processNew = JobCreation.generateProcess(io, template);
            if (processNew != null && processNew.getId() != null) {
                log.info("NLI hotfolder - created process: " + processNew.getId());

                //close first step
                HelperSchritte hs = new HelperSchritte();
                Step firstOpenStep = processNew.getFirstOpenStep();
                hs.CloseStepObjectAutomatic(firstOpenStep);

                // delete source files if configured so
                if (excelImport.shouldDeleteSourceFiles()) {
                    excelImport.deleteSourceFiles(hff, record);
                }
            } else { // processNew == null || processNew.getId() == null
                io.setErrorMessage("Process " + io.getProcessTitle() + " already exists. Aborting import");
                io.setImportReturnValue(ImportReturnValue.NoData);
            }

            excelImport.deleteTempImportData(io);

        } else if (io.getImportReturnValue() == ImportReturnValue.DataAllreadyExists) {
            //record exists and was overwritten. Temp import files have already been deleted. Just delete source folder
            if (excelImport.shouldDeleteSourceFiles()) {
                excelImport.deleteSourceFiles(hff, record);
            }
        }

        return io;
    }

    private String getReducedPreviousRunInfos(Path resultsJsonPath, int allowedNumberOfLogs) throws IOException {
        if (!storageProvider.isFileExists(resultsJsonPath)) {
            storageProvider.createFile(resultsJsonPath);
            return "]";
        }

        if (allowedNumberOfLogs <= 1) {
            return "]";
        }

        // otherwise, only the first allowedNumberOfLogs - 1 logs from lastResults will be kept
        String lastResults = new String(storageProvider.newInputStream(resultsJsonPath).readAllBytes());
        lastResults = ",\n" + lastResults.substring(1);
        log.debug("lastResult = " + lastResults);

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

}
