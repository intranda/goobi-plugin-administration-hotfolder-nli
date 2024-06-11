package de.intranda.goobi.plugins.hotfolder.nli.model.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;

import de.sub.goobi.config.ConfigPlugins;

public class HotfolderPluginConfig {

    private final XMLConfiguration baseConfig;

    public HotfolderPluginConfig(XMLConfiguration config) {
        this.baseConfig = config;
        baseConfig.setExpressionEngine(new XPathExpressionEngine());
        baseConfig.setReloadingStrategy(new FileChangedReloadingStrategy());
    }

    public HotfolderPluginConfig(String pluginName) {
        this(ConfigPlugins.getPluginConfig(pluginName));
    }

    /**
     * 
     * @param workflowTitle
     * @return
     */
    public SubnodeConfiguration getTemplateConfig(String templateName) {

        SubnodeConfiguration myconfig = null;
        try {
            myconfig = baseConfig.configurationAt("//config[./template = '" + templateName + "']");
        } catch (IllegalArgumentException e) {
            myconfig = baseConfig.configurationAt("//config[./template = '*']");
        }

        return myconfig;
    }

    public Path getHotfolderPath() {
        return Paths.get(baseConfig.getString("hotfolderPath"));
    }

    public boolean isUseTimeDifference() {
        return baseConfig.getBoolean("useTimeDifference", false);
    }

    public int getAllowedTimeDifference() {
        return Math.max(baseConfig.getInt("allowedTimeDifference", 1), 1);
    }

    public int getAllowedNumberOfLogs() {
        return Math.max(baseConfig.getInt("allowedNumberOfLogs", 1), 1);
    }

    public String getOwnerType() {
        return baseConfig.getString("ownerType", "");

    }

    /**
     * time in minutes that a folder should remain unmodified before the import of it starts
     * 
     * @return an integer representing the time, at least 0 which means that the folder age is not checked
     */
    public int getMinutesOfInactivity() {
        return baseConfig.getInt("minutesOfInactivity", 30);
    }

}
