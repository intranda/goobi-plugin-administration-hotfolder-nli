package de.intranda.goobi.plugins;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.goobi.production.enums.PluginType;
import org.goobi.production.plugin.interfaces.IAdministrationPlugin;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.intranda.goobi.plugins.model.GUIImportResult;
import de.sub.goobi.config.ConfigPlugins;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class HotfolderNliAdministrationPlugin implements IAdministrationPlugin {
    private static ObjectMapper om = new ObjectMapper();
    private static JavaType lastRunInfoListType = om.getTypeFactory().constructCollectionType(List.class, GUIImportResult.class);

    private Path hotfolderPath;

    @Getter
    private String title = "intranda_administration_hotfolder_nli";

    private Map<String, List<GUIImportResult>> lastRunInfo;
    @Getter
    private Map<String, Boolean> showFolders = new HashMap<>();
    private Instant lastRunInfoModified;
    private Instant lastRunInfoLoadTime;

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
        log.info("NLI hotfolder admnistration plugin started");
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

    public void pauseWork() throws IOException {
        Path pauseFile = hotfolderPath.resolve("hotfolder_pause.lock");
        if (!Files.exists(pauseFile)) {
            Files.createFile(pauseFile);
        }
    }

    public void resumeWork() throws IOException {
        Path pauseFile = hotfolderPath.resolve("hotfolder_pause.lock");
        if (Files.exists(pauseFile)) {
            Files.delete(pauseFile);
        }
    }

    public Map<String, List<GUIImportResult>> getLastRunInfo() throws JsonParseException, JsonMappingException, IOException {
        if (this.lastRunInfoLoadTime == null || Instant.now().minus(Duration.ofSeconds(30)).isAfter(lastRunInfoLoadTime)) {
            loadLastRunInfo();
        }
        return this.lastRunInfo;
    }

    public void loadLastRunInfo() throws JsonParseException, JsonMappingException, IOException {
        lastRunInfoLoadTime = Instant.now();
        Path lastRunInfoPath = hotfolderPath.resolve("lastRunResults.json");
        if (!Files.exists(lastRunInfoPath)) {
            lastRunInfo = new LinkedHashMap<String, List<GUIImportResult>>();
            return;
        }
        Instant lastModified = Files.getLastModifiedTime(lastRunInfoPath).toInstant();
        if (this.lastRunInfoModified == null || lastModified.isAfter(this.lastRunInfoModified)) {
            lastRunInfo = new LinkedHashMap<String, List<GUIImportResult>>();
            try (InputStream src = Files.newInputStream(lastRunInfoPath)) {
                List<GUIImportResult> results = om.readValue(src, lastRunInfoListType);
                for (GUIImportResult guiResult : results) {
                    Path absPath = Paths.get(guiResult.getImportFileName());
                    String key = hotfolderPath.relativize(absPath.getParent()).toString();
                    List<GUIImportResult> keyResults = this.lastRunInfo.get(key);
                    if (keyResults == null) {
                        keyResults = new ArrayList<>();
                        this.lastRunInfo.put(key, keyResults);
                        if (this.showFolders.get(key) == null) {
                            this.showFolders.put(key, false);
                        }
                    }
                    keyResults.add(guiResult);
                }
            }
            lastRunInfoModified = lastModified;
        }
    }

    public void toggleShowFolder(String folder) {
        this.showFolders.put(folder, !this.showFolders.get(folder));
    }
}
