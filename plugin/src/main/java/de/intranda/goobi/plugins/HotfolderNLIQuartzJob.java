package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.apache.commons.configuration.XMLConfiguration;
import org.goobi.beans.Step;
import org.goobi.production.enums.ImportReturnValue;
import org.goobi.production.flow.helper.JobCreation;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
import org.quartz.Job;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SchedulerFactory;
import org.quartz.Trigger;
import org.quartz.TriggerUtils;
import org.quartz.impl.StdSchedulerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.intranda.goobi.plugins.model.GUIImportResult;
import de.intranda.goobi.plugins.model.HotfolderFolder;
import de.intranda.goobi.plugins.model.NLIExcelImport;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.HelperSchritte;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.persistence.managers.ProcessManager;
import lombok.extern.log4j.Log4j2;

@WebListener
@Log4j2
public class HotfolderNLIQuartzJob implements Job, ServletContextListener {

    private static String title = "intranda_administration_hotfolder_nli";

    private NLIExcelImport excelImport = null;

    /**
     * Quartz-Job implementation
     */
    @Override
    public void execute(JobExecutionContext jec) throws JobExecutionException {
        XMLConfiguration config = ConfigPlugins.getPluginConfig(title);
        Path hotfolderPath = Paths.get(config.getString("hotfolderPath"));

        if (!Files.exists(hotfolderPath)) {
            log.info("NLI hotfolder is not present: " + hotfolderPath);
            return;
        }

        Path lockFile = hotfolderPath.resolve("hotfolder_running.lock");
        if (Files.exists(lockFile)) {
            log.info("NLI hotfolder is already running - not running a second time in parallel");
            return;
        }

        Path pauseFile = hotfolderPath.resolve("hotfolder_pause.lock");
        if (Files.exists(pauseFile)) {
            log.info("NLI hotfolder is paused - not running");
            return;
        }

        try {
            Files.createFile(lockFile);
            List<HotfolderFolder> importFolders = traverseHotfolder(hotfolderPath);
            List<ImportObject> imports = createProcesses(importFolders);

            List<GUIImportResult> guiResults = imports.stream()
                    .map(io -> new GUIImportResult(io))
                    .collect(Collectors.toList());

            ObjectMapper om = new ObjectMapper();

            try (OutputStream out = Files.newOutputStream(hotfolderPath.resolve("lastRunResults.json"), StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.CREATE)) {
                om.writeValue(out, guiResults);
            }

        } catch (Exception e) {
            log.error("Error running NLI hotfolder: {}", e);
        } finally {
            try {
                Files.deleteIfExists(lockFile);
            } catch (IOException e) {
                log.error("Error deleting NLI hotfolder lock file: {}", e);
            }
        }

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
        try (DirectoryStream<Path> templatesDirStream = Files.newDirectoryStream(hotfolderPath)) {
            for (Path templatePath : templatesDirStream) {
                if (Files.isDirectory(templatePath)) {
                    try (DirectoryStream<Path> projectsDirStream = Files.newDirectoryStream(templatePath)) {
                        for (Path projectPath : projectsDirStream) {
                            if (Files.isDirectory(projectPath)) {
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
        List<ImportObject> imports = new ArrayList<ImportObject>();
        for (HotfolderFolder hff : importFolders) {

            try {
                File importFile = hff.getImportFile();
                List<Path> processFolders = hff.getCurrentProcessFolders();

                if (importFile == null) {
                    log.trace("importFile: {}, processFolders.size(): {}", importFile, processFolders.size());
                    continue;
                }

                //otherwise:
                log.info("NLI hotfolder - importing: " + importFile);

                if (excelImport == null) {
                    excelImport = new NLIExcelImport(hff);
                }
                List<Record> records;
                try {
                    records = excelImport.generateRecordsFromFile(importFile, processFolders);
                } catch (IOException e) {
                    ImportObject io = new ImportObject();
                    io.setImportFileName(importFile.getAbsolutePath());
                    io.setErrorMessage("Could not read import file");
                    imports.add(io);
                    break;
                }

                int lineNumber = 1;
                for (Record record : records) {
                    lineNumber++;
                    ImportObject io = excelImport.generateFile(importFile.toString(), lineNumber, record, hff);
                    if (io == null) {
                        continue;
                    }

                    if (io.getImportReturnValue() != ImportReturnValue.ExportFinished) {
                        imports.add(io);
                        continue;
                    }

                    //otherwise:
                    org.goobi.beans.Process template = ProcessManager.getProcessByExactTitle(hff.getTemplateName());
                    org.goobi.beans.Process processNew = JobCreation.generateProcess(io, template);

                    if (processNew != null && processNew.getId() != null) {
                        log.info("NLI hotfolder - created process: " + processNew.getId());

                        //close first step
                        HelperSchritte hs = new HelperSchritte();
                        Step firstOpenStep = processNew.getFirstOpenStep();
                        hs.CloseStepObjectAutomatic(firstOpenStep);
                    }

                    //remove temp file
                    if (io.getMetsFilename() != null) {
                        File file = new File(io.getMetsFilename());
                        if (file.exists()) {
                            StorageProvider.getInstance().deleteFile(file.toPath());
                        }
                        File folder = new File(io.getMetsFilename().replace(".xml", ""));
                        if (folder.exists()) {
                            StorageProvider.getInstance().deleteDir(folder.toPath());
                        }
                    }

                    imports.add(io);

                }

            } catch (Exception e) {
                log.info("NLI hotfolder - error: " + e.getMessage());
                throw e;
            }
        }
        return imports;
    }

    /**
     * ServletContextListener
     */
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        log.info("Starting 'NLI hotfolder' scheduler");
        try {
            // get default scheduler
            SchedulerFactory schedFact = new StdSchedulerFactory();
            Scheduler sched = schedFact.getScheduler();

            // configure time to start 
            java.util.Calendar startTime = java.util.Calendar.getInstance();
            startTime.add(java.util.Calendar.MINUTE, 1);

            // create new job 
            JobDetail jobDetail = new JobDetail("NLI hotfolder", "Goobi Admin Plugin", HotfolderNLIQuartzJob.class);
            Trigger trigger = TriggerUtils.makeMinutelyTrigger(5);
            trigger.setName("NLI hotfolder");
            trigger.setStartTime(startTime.getTime());

            // register job and trigger at scheduler
            sched.scheduleJob(jobDetail, trigger);
        } catch (SchedulerException e) {
            log.error("Error while executing the scheduler", e);
        }
    }

    /**
     * ServletContextListener
     */
    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        // stop the Googlebooks Harvester job
        try {
            SchedulerFactory schedFact = new StdSchedulerFactory();
            Scheduler sched = schedFact.getScheduler();
            sched.deleteJob("NLI hotfolder", "Goobi Admin Plugin");
            log.info("Scheduler for 'NLI hotfolder' stopped");
        } catch (SchedulerException e) {
            log.error("Error while stopping the job", e);
        }
    }

}
