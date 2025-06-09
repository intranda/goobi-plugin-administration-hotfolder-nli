package de.intranda.goobi.plugins.hotfolder.nli.model.hotfolder;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import de.intranda.goobi.plugins.hotfolder.nli.model.config.HotfolderPluginConfig;
import de.intranda.goobi.plugins.hotfolder.nli.model.config.HotfolderScheduler;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class HotfolderParser {

    private final StorageProviderInterface storageProvider;

    public HotfolderParser(StorageProviderInterface storageProvider) {
        this.storageProvider = storageProvider;
    }

    public HotfolderParser() {
        this(StorageProvider.getInstance());
    }

    public List<HotfolderFolder> getImportFolders(Path hotfolderPath, HotfolderPluginConfig config) throws IOException {
        List<HotfolderFolder> importFolders = traverseHotfolder(hotfolderPath);
        HotfolderScheduler scheduler = new HotfolderScheduler(config);
        log.info("NLI hotfolder: Traversed import folders. Found " + importFolders.size() + " folders");
        // check schedule to determine whether templates should be ignored or not
        List<String> ignoredTemplates = new ArrayList<>();
        importFolders = importFolders.stream().filter(folder -> {
            if (!scheduler.shouldRunNow(folder)) {
                ignoredTemplates.add(folder.getTemplateName());
                return false;
            } else {
                return true;
            }
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
                                stableBarcodeFolders.add(new HotfolderFolder(projectPath, templatePath.getFileName().toString(), storageProvider));
                            }
                        }
                    }
                }
            }
        }
        return stableBarcodeFolders;
    }

}
