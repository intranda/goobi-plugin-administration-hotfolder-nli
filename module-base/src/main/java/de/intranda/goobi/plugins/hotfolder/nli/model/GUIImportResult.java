package de.intranda.goobi.plugins.hotfolder.nli.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.goobi.production.importer.ImportObject;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GUIImportResult {
    private String importFileName;
    private String errorMessage;
    private String timestamp;

    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MMM.yyyy HH:mm:ss");

    public GUIImportResult(ImportObject io) {
        this.importFileName = io.getImportFileName();
        this.errorMessage = io.getErrorMessage();
        this.timestamp = LocalDateTime.now().format(formatter);
    }

    public static DateTimeFormatter getFormatter() {
        return formatter;
    }
}
