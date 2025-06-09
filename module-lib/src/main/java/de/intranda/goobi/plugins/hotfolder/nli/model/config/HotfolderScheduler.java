package de.intranda.goobi.plugins.hotfolder.nli.model.config;

import java.time.LocalDateTime;

import org.apache.commons.configuration.SubnodeConfiguration;

import de.intranda.goobi.plugins.hotfolder.nli.model.hotfolder.HotfolderFolder;

public class HotfolderScheduler {

    private final HotfolderPluginConfig config;

    public HotfolderScheduler(HotfolderPluginConfig config) {
        this.config = config;
    }

    public boolean shouldRunNow(HotfolderFolder folder) {
        SubnodeConfiguration templateConfig = config.getTemplateConfig(folder.getTemplateName());
        Integer startTime = templateConfig.getInt("schedule/start", 0);
        Integer endTime = templateConfig.getInt("schedule/end", 0);
        int currentHour = LocalDateTime.now().getHour();
        boolean run = shouldRunAtTime(currentHour, startTime, endTime);
        return run;
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
    private boolean shouldRunAtTime(int currentHour, Integer startTime, Integer endTime) {
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

}
