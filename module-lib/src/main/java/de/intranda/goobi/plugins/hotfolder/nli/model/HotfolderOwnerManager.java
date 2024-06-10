package de.intranda.goobi.plugins.hotfolder.nli.model;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class HotfolderOwnerManager {

    // map that hold maps between folder paths and owner names
    private Map<HotfolderFolder, Map<Path, String>> folderOwnerMaps = new HashMap<>();

    public void updateFolderOwnerMaps(HotfolderFolder hff) {
        // get owner info of the folder
        Map<Path, String> folderOwnerMap = hff.getFolderOwnerMap();
        // store the info
        folderOwnerMaps.put(hff, folderOwnerMap);
    }

    public boolean deleteOwnerFile(HotfolderFolder hff, String processTitle) {
        Map<Path, String> folderOwnerMap = folderOwnerMaps.get(hff);
        for (Map.Entry<Path, String> entry : folderOwnerMap.entrySet()) {
            Path folderPath = entry.getKey();
            String folderName = folderPath.getFileName().toString();
            if (processTitle.endsWith(folderName)) {
                hff.deleteOwnerFile(folderPath);
                return true;
            }
        }
        return false;
    }

    public String getOwnerName(HotfolderFolder hff, String processTitle) {
        Map<Path, String> folderOwnerMap = folderOwnerMaps.get(hff);
        for (Map.Entry<Path, String> entry : folderOwnerMap.entrySet()) {
            Path folderPath = entry.getKey();
            String folderName = folderPath.getFileName().toString();
            if (processTitle.endsWith(folderName)) {
                String ownerName = entry.getValue();
                log.debug("ownerName = " + ownerName);
                return ownerName;
            }
        }

        // not found
        log.debug("ownerName not set");
        return "";
    }

}
