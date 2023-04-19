package de.intranda.goobi.plugins.hotfolder.nli;

import java.io.IOException;
import java.io.InputStream;
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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.intranda.goobi.plugins.hotfolder.nli.model.GUIImportResult;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class HotfolderNliAdministrationPlugin implements IAdministrationPlugin {
    private static ObjectMapper om = new ObjectMapper();

    private static TypeReference<List<List<GUIImportResult>>> typeReferenceOfList = new TypeReference<>() {
    };

    private static StorageProviderInterface storageProvider = StorageProvider.getInstance();

    private static final String RESULTS_JSON_FILENAME = "lastRunResults.json";

    private Path hotfolderPath;

    private int number = 0;

    private boolean numberUpdated = false;

    @Getter
    private String title = "intranda_administration_hotfolder_nli";

    // information about the last run
    private Map<String, List<GUIImportResult>> lastRunInfo;

    // controls whether or not to show folders
    @Getter
    private Map<String, Boolean> showFolders = new HashMap<>();

    // the instant that the field lastRunInfo was modified
    private Instant lastRunInfoModified;
    // the instant that the field lastRunInfo was loaded
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
        return storageProvider.isFileExists(pauseFile);
    }

    public boolean isRunning() {
        Path lockFile = hotfolderPath.resolve("hotfolder_running.lock");
        return storageProvider.isFileExists(lockFile);
    }

    public Date getStartedRunningAt() throws IOException {
        Path lockFile = hotfolderPath.resolve("hotfolder_running.lock");
        return new Date(storageProvider.getLastModifiedDate(lockFile));
    }

    public String getRunningSince() throws IOException {
        Path lockFile = hotfolderPath.resolve("hotfolder_running.lock");
        Instant modifiedInstant = Instant.ofEpochMilli(storageProvider.getLastModifiedDate(lockFile));
        Duration runningTime = Duration.between(modifiedInstant, Instant.now());
        long s = runningTime.getSeconds();

        return String.format("%d:%02d:%02d", s / 3600, (s % 3600) / 60, (s % 60));
    }

    public void pauseWork() throws IOException {
        Path pauseFile = hotfolderPath.resolve("hotfolder_pause.lock");
        if (!storageProvider.isFileExists(pauseFile)) {
            storageProvider.createFile(pauseFile);
        }
    }

    public void resumeWork() throws IOException {
        Path pauseFile = hotfolderPath.resolve("hotfolder_pause.lock");
        if (storageProvider.isFileExists(pauseFile)) {
            storageProvider.deleteFile(pauseFile);
        }
    }

    public Map<String, List<GUIImportResult>> getLastRunInfo() throws JsonParseException, JsonMappingException, IOException {
        if (numberUpdated || lastRunInfoLoadTime == null || Instant.now().minus(Duration.ofSeconds(30)).isAfter(lastRunInfoLoadTime)) {
            loadLastRunInfo();
            numberUpdated = false;
        }

        return this.lastRunInfo;
    }

    public void loadLastRunInfo() throws JsonParseException, JsonMappingException, IOException {
        lastRunInfoLoadTime = Instant.now();
        Path lastRunInfoPath = hotfolderPath.resolve(RESULTS_JSON_FILENAME);
        if (!storageProvider.isFileExists(lastRunInfoPath)) {
            lastRunInfo = new LinkedHashMap<String, List<GUIImportResult>>();
            return;
        }

        // the instant of the last modifications made to the json file, signifying modifications in some hotfolder
        Instant lastModified = Instant.ofEpochMilli(storageProvider.getLastModifiedDate(lastRunInfoPath));

        // check if any modifications happened after lastRunInfoModified, if so then the field lastRunInfo should be updated
        if (numberUpdated || lastRunInfoModified == null || lastModified.isAfter(lastRunInfoModified)) {
            // clearing up old entries and reload from the json file
            lastRunInfo = new LinkedHashMap<String, List<GUIImportResult>>();
            try (InputStream src = storageProvider.newInputStream(lastRunInfoPath)) {

                List<List<GUIImportResult>> listOfResults = om.readValue(src, typeReferenceOfList);
                List<GUIImportResult> results = listOfResults.get(this.number);
                log.debug("listOfResults.size() = " + listOfResults.size());

                // add all GUI results accordingly
                for (GUIImportResult guiResult : results) {
                    Path absPath = Paths.get(guiResult.getImportFileName());
                    String key = hotfolderPath.relativize(absPath.getParent()).toString();

                    // get the list stored in lastRunInfo associated with key
                    List<GUIImportResult> keyResults = this.lastRunInfo.get(key);
                    // if key does not exist yet in lastRunInfo, add it and associate it with an empty list
                    if (keyResults == null) {
                        keyResults = new ArrayList<>();
                        this.lastRunInfo.put(key, keyResults);
                        // do not show any folders in the beginning
                        if (this.showFolders.get(key) == null) {
                            this.showFolders.put(key, false);
                        }
                    }
                    // add result to this list
                    keyResults.add(guiResult);
                }
            }
            lastRunInfoModified = lastModified;
        }
    }

    public void toggleShowFolder(String folder) {
        this.showFolders.put(folder, !this.showFolders.get(folder));
    }

    public int getNumber() {
        return number;
    }

    public void setNumber(int number) {
        this.number = number;
    }

    public void nextNumber() {
        number += 1;
        numberUpdated = true;
    }

    public void previousNumber() {
        number -= 1;
        numberUpdated = true;
    }

}
