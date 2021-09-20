package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IAdministrationPlugin;

import de.sub.goobi.config.ConfigPlugins;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class HotfolderNliAdministrationPlugin implements IAdministrationPlugin {

    private Path hotfolderPath;

    @Getter
    private String title = "intranda_administration_hotfolder_nli";

    @Override
    public PluginType getType() {
        return PluginType.Administration;
    }

    @Override
    public String getGui() {
        return "/uii/plugin_administration_hotfolder_nli.xhtml";
    }

    /**
     * Constructor
     */
    public HotfolderNliAdministrationPlugin() {
        log.info("Sample admnistration plugin started");
        hotfolderPath = Paths.get(ConfigPlugins.getPluginConfig(title).getString("hotfolderPath"));
    }

    public boolean isPaused() {
        Path pauseFile = hotfolderPath.resolve("hotfolder_pause.lock");
        return Files.exists(pauseFile);
    }

    public boolean isRunning() {
        Path lockFile = hotfolderPath.resolve("hotfolder_running.lock");
        return Files.exists(lockFile);
    }

    public Date getStartedRunningAt() throws IOException {
        Path lockFile = hotfolderPath.resolve("hotfolder_running.lock");
        return new Date(Files.getLastModifiedTime(lockFile).toMillis());
    }

    public String getRunningSince() throws IOException {
        Path lockFile = hotfolderPath.resolve("hotfolder_running.lock");
        Duration runningTime = Duration.between(Files.getLastModifiedTime(lockFile).toInstant(), Instant.now());
        long s = runningTime.getSeconds();
        return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, (s % 60));
    }
}
