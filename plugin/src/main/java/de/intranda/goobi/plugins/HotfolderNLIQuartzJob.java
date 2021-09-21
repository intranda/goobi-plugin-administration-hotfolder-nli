package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.apache.commons.configuration.XMLConfiguration;
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

import de.intranda.goobi.plugins.model.HotfolderFolder;
import de.sub.goobi.config.ConfigPlugins;
import lombok.extern.log4j.Log4j2;

@WebListener
@Log4j2
public class HotfolderNLIQuartzJob implements Job, ServletContextListener {
    private static String title = "intranda_administration_hotfolder_nli";

    /**
     * Quartz-Job implementation
     */
    @Override
    public void execute(JobExecutionContext jec) throws JobExecutionException {
        XMLConfiguration config = ConfigPlugins.getPluginConfig(title);
        Path hotfolderPath = Paths.get(config.getString("hotfolderPath"));
        Path lockFile = hotfolderPath.resolve("hotfolder_running.lock");
        if (Files.exists(lockFile)) {
            log.info("NLI hotfolder is already running - not running a second time in parallel");
            return;
        }
        Path pauseFile = hotfolderPath.resolve("hotfolder_pause.lock");
        if (Files.exists(pauseFile)) {
            log.info("NLI hotfolder is already paused - not running");
            return;
        }
        try {
            Files.createFile(lockFile);
            List<HotfolderFolder> importFolders = traverseHotfolder(hotfolderPath);
            createProcesses(importFolders);
        } catch (IOException e) {
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
     * The barcode folders are the ones that are returned by this method.
     * 
     * @param hotfolderPath the path of the hotfolder
     * @return folders that have not been modified for 30 minutes
     * @throws IOException
     */
    private List<HotfolderFolder> traverseHotfolder(Path hotfolderPath) throws IOException {
        List<HotfolderFolder> stableBarcodeFolders = new ArrayList<>();
        try (DirectoryStream<Path> projectsDirStream = Files.newDirectoryStream(hotfolderPath)) {
            for (Path projectPath : projectsDirStream) {
                if (Files.isDirectory(projectPath)) {
                    try (DirectoryStream<Path> templatesDirStream = Files.newDirectoryStream(projectPath)) {
                        for (Path templatePath : templatesDirStream) {
                            try (DirectoryStream<Path> barcodeDirStream = Files.newDirectoryStream(templatePath)) {
                                for (Path barcodePath : barcodeDirStream) {
                                    Instant lastModified = Files.getLastModifiedTime(barcodePath).toInstant();
                                    Instant thirtyMinutesAgo = Instant.now().minus(Duration.ofMinutes(30));
                                    if (lastModified.isBefore(thirtyMinutesAgo)) {
                                        stableBarcodeFolders.add(new HotfolderFolder(projectPath.getFileName().toString(),
                                                templatePath.getFileName().toString(), barcodePath));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return stableBarcodeFolders;
    }

    private void createProcesses(List<HotfolderFolder> importFolders) {
        for (HotfolderFolder hff : importFolders) {
            log.info("NLI hotfolder: importing: {}", hff.getImportFolder());
        }
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
