package de.intranda.goobi.plugins.model;

import org.goobi.production.importer.ImportObject;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class GUIImportResult {
    private String importFileName;
    private String errorMessage;

    public GUIImportResult(ImportObject io) {
        this.importFileName = io.getImportFileName();
        this.errorMessage = io.getErrorMessage();
    }
}
