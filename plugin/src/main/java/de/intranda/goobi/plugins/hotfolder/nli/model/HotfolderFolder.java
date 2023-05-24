package de.intranda.goobi.plugins.hotfolder.nli.model;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

@Log4j2
public class HotfolderFolder {

    @Getter
    private Path projectFolder;

    @Getter
    private String templateName;

    private Integer minutesInactivity = 30;

    private List<Path> lstProcessFolders;

    public HotfolderFolder(Path projectFolder, String templateName) throws IOException {

        this.projectFolder = projectFolder;
        this.templateName = templateName;

        getImportFolders();
    }

    private void getImportFolders() throws IOException {

        lstProcessFolders = new ArrayList<>();

        try (DirectoryStream<Path> barcodeDirStream = Files.newDirectoryStream(projectFolder)) {
            for (Path barcodePath : barcodeDirStream) {
                if (Files.isDirectory(barcodePath)) {
                    lstProcessFolders.add(barcodePath);
                }
            }
        }
    }

    public List<Path> getCurrentProcessFolders() throws IOException {

        List<Path> lstFoldersToImport = new ArrayList<>();

        for (Path barcodePath : lstProcessFolders) {
            log.trace("looking at {}", barcodePath);
            Instant lastModified = Files.getLastModifiedTime(barcodePath).toInstant();
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

        for (File file : projectFolder.toFile().listFiles()) {
            if (file.getName().endsWith(".xlsx") && !file.getName().startsWith("~")) {
                return file;
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
        for (File file : folderPath.toFile().listFiles()) {
            String fileName = file.getName();
            if (fileName.endsWith(".owner")) {
                return fileName.substring(0, fileName.lastIndexOf(".")).trim();
            }
        }

        return "";
    }

    public void deleteOwnerFile(Path folderPath) {
        // delete the .owner file 
        log.debug("deleting .owner file");
        for (File file : folderPath.toFile().listFiles()) {
            String fileName = file.getName();
            if (fileName.endsWith(".owner")) {
                if (!file.delete()) {
                    log.error("failed to delete the .owner file in " + folderPath);
                }
                // there should be at most only one such file 
                return;
            }
        }
    }
}
