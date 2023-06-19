package de.intranda.goobi.plugins.hotfolder.nli.model;

import java.text.SimpleDateFormat;
import java.util.Date;

import org.goobi.production.importer.ImportObject;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GUIImportResult {
    private String importFileName;
    private String errorMessage;
    private String timestamp;

    private static final SimpleDateFormat sdf = new SimpleDateFormat("dd.MMM.yyyy HH:mm:ss");

    public GUIImportResult(ImportObject io) {
        this.importFileName = io.getImportFileName();
        this.errorMessage = io.getErrorMessage();
        this.timestamp = sdf.format(new Date());
    }
}
