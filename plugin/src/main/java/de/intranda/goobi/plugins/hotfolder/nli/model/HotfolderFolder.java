package de.intranda.goobi.plugins.hotfolder.nli.model;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class HotfolderFolder {
    private static StorageProviderInterface storageProvider = StorageProvider.getInstance();

    @Getter
    private Path projectFolder;

    @Getter
    private String templateName;

    private Integer minutesInactivity = 30;

    private List<Path> projectFoldersFileList;

    private List<Path> lstProcessFolders;

    public HotfolderFolder(Path projectFolder, String templateName) throws IOException {
        this.projectFolder = projectFolder;
        this.templateName = templateName;
        this.projectFoldersFileList = storageProvider.listFiles(this.projectFolder.toString());

        getImportFolders();
    }

    private void getImportFolders() throws IOException {
        lstProcessFolders = new ArrayList<>();
        
        for (Path barcodePath : projectFoldersFileList) {
            if (storageProvider.isDirectory(barcodePath)) {
                lstProcessFolders.add(barcodePath);
            }
        }
    }

    public List<Path> getCurrentProcessFolders() throws IOException {
        List<Path> lstFoldersToImport = new ArrayList<>();

        for (Path barcodePath : lstProcessFolders) {
            log.trace("looking at {}", barcodePath);
            Instant lastModified = Instant.ofEpochMilli(storageProvider.getLastModifiedDate(barcodePath));
            Instant thirtyMinutesAgo = Instant.now().minus(Duration.ofMinutes(minutesInactivity));
            if (lastModified.isBefore(thirtyMinutesAgo)) {
                log.trace("Adding process folder {} to list", barcodePath);
                lstFoldersToImport.add(barcodePath);
            } else {
                log.trace("Not adding process folder {}. Last modified time {} is not before {}", barcodePath, lastModified, thirtyMinutesAgo);
            }
        }

        return lstFoldersToImport;
    }

    public File getImportFile() throws IOException {
        for (Path filePath : projectFoldersFileList) {
            String fileName = filePath.getFileName().toString();
            if (fileName.endsWith(".xlsx") && !fileName.startsWith("~")) {
                return filePath.toFile();
            }
        }

        //otherwise
        return null;
    }

    public Map<Path, String> getFolderOwnerMap() {
        Map<Path, String> folderOwnerMap = new HashMap<>();
        for (Path folderPath : lstProcessFolders) {
            String ownerName = getOwnerName(folderPath);
            if (StringUtils.isNotBlank(ownerName)) {
                folderOwnerMap.put(folderPath, ownerName);
                deleteOwnerFile(folderPath);
                continue;
            }
        }

        return folderOwnerMap;
    }

    private String getOwnerName(Path folderPath) {
        Path ownerFilePath = getOwnerFilePath(folderPath);
        if (ownerFilePath != null) {
            String fileName = ownerFilePath.getFileName().toString();
            return fileName.substring(0, fileName.lastIndexOf(".")).trim();
        }

        return "";
    }

    private Path getOwnerFilePath(Path folderPath) {
        for (Path filePath : storageProvider.listFiles(folderPath.toString())) {
            String fileName = filePath.getFileName().toString();
            if (fileName.endsWith(".owner")) {
                // there should be at most only one such file 
                return filePath;
            }
        }

        return null;
    }

    public void deleteOwnerFile(Path folderPath) {
        log.debug("deleting .owner file in " + folderPath);

        Path ownerFilePath = getOwnerFilePath(folderPath);
        if (ownerFilePath != null) {
            try {
                storageProvider.deleteFile(ownerFilePath);
            } catch (IOException e) {
                log.error("failed to delete the .owner file in " + folderPath);
            }
        }
    }

}
