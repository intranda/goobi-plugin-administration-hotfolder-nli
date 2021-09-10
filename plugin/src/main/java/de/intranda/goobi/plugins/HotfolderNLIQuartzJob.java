package de.intranda.goobi.plugins;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

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

import lombok.extern.log4j.Log4j2;

@Log4j2
public class HotfolderNLIQuartzJob implements Job, ServletContextListener {

    /**
     * Quartz-Job implementation
     */
    @Override
    public void execute(JobExecutionContext jec) throws JobExecutionException {
        // TODO Auto-generated method stub

    }

    /**
     * ServletContextListener
     */
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        log.info("Starting 'Googlebooks Harvester' scheduler");
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
