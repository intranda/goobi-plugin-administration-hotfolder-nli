package de.intranda.goobi.plugins.model;

import java.nio.file.Path;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class HotfolderFolder {
    private String project;
    private String template;
    private Path importFolder;
}
