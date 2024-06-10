package de.intranda.goobi.plugins.hotfolder.nli.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.FileUtils;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import de.intranda.goobi.plugins.hotfolder.nli.model.config.HotfolderPluginConfig;
import de.intranda.goobi.plugins.hotfolder.nli.model.hotfolder.HotfolderFolder;
import de.intranda.goobi.plugins.hotfolder.nli.model.hotfolder.HotfolderParser;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProviderInterface;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;

public class NLIHotfolderImportTest {

    private final static Path HOTFOLDER_PATH = Path.of("src/test/resources/hotfolder").toAbsolutePath();
    private final static Path CONFIG_PATH = Path.of("src/test/resources/plugin_intranda_administration_hotfolder_nli.xml").toAbsolutePath();
    private final static Path RULESET_PATH = Path.of("src/test/resources/ruleset.xml").toAbsolutePath();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Path hotfolderPath;
    private Path importPath;
    private HotfolderPluginConfig config;
    private StorageProviderInterface storageProvider;
    private Prefs prefs;

    @Before
    public void setup() throws IOException, ConfigurationException, PreferencesException {
        this.hotfolderPath = tempFolder.newFolder("hotfolder").toPath();
        this.importPath = tempFolder.newFolder("import").toPath();
        config = new HotfolderPluginConfig(new XMLConfiguration(CONFIG_PATH.toFile()));
        FileUtils.copyDirectory(HOTFOLDER_PATH.toFile(), hotfolderPath.toFile());
        this.storageProvider = new NIOFileUtils();
        this.prefs = new Prefs();
        prefs.loadPrefs(RULESET_PATH.toString());
    }

    @Test
    public void testGetHotfolder() throws IOException {
        List<HotfolderFolder> hotfolders = new HotfolderParser(storageProvider).getImportFolders(hotfolderPath, config);
        assertEquals(1, hotfolders.size());
        assertEquals("Karenp", hotfolders.get(0).getOwnerName("997008730630705171"));
        assertEquals("Audio_and_Video", hotfolders.get(0).getTemplateName());
        assertEquals("Reuploads-Audio", hotfolders.get(0).getProjectFolder().getFileName().toString());
        assertEquals(1, hotfolders.get(0).getCurrentProcessFolders().size());
        assertEquals("997008730630705171", hotfolders.get(0).getCurrentProcessFolders().get(0).getFileName().toString());
    }

    @Test
    public void testImport() throws IOException {
        NLIHotfolderImport importer = new NLIHotfolderImport(config, storageProvider, importPath.toString());
        List<HotfolderFolder> hotfolders = new HotfolderParser(storageProvider).getImportFolders(hotfolderPath, config);

        HotfolderFolder hff = hotfolders.get(0);
        NLIExcelImport excelImport =
                new NLIExcelImport(this.config, this.storageProvider, this.importPath.toString(), prefs, hff.getTemplateName());
        List<Record> records = excelImport.generateRecordsFromFile(hff.getImportFile());
        assertEquals(1, records.size());

        Record record = records.get(0);
        ImportObject io = excelImport.generateFile(hff.getImportFile().toString(), 0, record, hff);
        Path metsPath = Path.of(io.getMetsFilename());
        assertTrue(Files.exists(metsPath));
        Path mediaPath = metsPath.getParent().resolve(metsPath.getFileName().toString().replace(".xml", "")).resolve("images");
        assertTrue(Files.exists(mediaPath));
        assertEquals(1, Files.list(mediaPath).count());

    }

}
