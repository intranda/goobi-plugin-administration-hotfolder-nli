package de.intranda.goobi.plugins.hotfolder.nli.model;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;

import org.apache.commons.configuration.XMLConfiguration;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import de.intranda.goobi.plugins.hotfolder.nli.model.config.HotfolderPluginConfig;
import de.intranda.goobi.plugins.hotfolder.nli.model.exceptions.ImportException;
import de.intranda.goobi.plugins.hotfolder.nli.model.hotfolder.HotfolderFolder;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProviderInterface;

/**
 * Regression test for the NoSuchElementException reported to occur in generateRecordsFromFile() when the import file's first sheet has no rows at
 * all (rowIterator().next() called on an already exhausted iterator).
 */
public class NLIExcelImportEmptySheetTest {

    private static final Path CONFIG_PATH = Path.of("src/test/resources/plugin_intranda_administration_hotfolder_nli.xml").toAbsolutePath();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder(new File("src/test").getAbsoluteFile());

    @Test
    public void generateRecordsFromFileThrowsImportExceptionForSheetWithoutRows() throws Exception {
        Path projectFolder = tempFolder.newFolder("project").toPath();
        writeEmptyWorkbook(projectFolder.resolve("import.xlsx"));

        HotfolderPluginConfig config = new HotfolderPluginConfig(new XMLConfiguration(CONFIG_PATH.toFile()));
        StorageProviderInterface storageProvider = new NIOFileUtils();
        HotfolderFolder hff = new HotfolderFolder(projectFolder, "templateName", storageProvider);

        NLIExcelImport excelImport = new NLIExcelImport(config, null, storageProvider, tempFolder.newFolder("import").toString(), null,
                "templateName");

        ImportException thrown = assertThrows(ImportException.class, () -> excelImport.generateRecordsFromFile(hff));
        assertTrue(thrown.getMessage().contains("import.xlsx"));
    }

    private void writeEmptyWorkbook(Path path) throws Exception {
        try (Workbook wb = WorkbookFactory.create(true)) {
            wb.createSheet("Sheet1");
            // intentionally no rows added, reproducing an import file whose first sheet has zero physical rows
            try (FileOutputStream out = new FileOutputStream(path.toFile())) {
                wb.write(out);
            }
        }
    }
}
