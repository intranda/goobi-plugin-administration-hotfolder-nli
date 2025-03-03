package de.intranda.goobi.plugins.hotfolder.nli.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.FileUtils;
import org.goobi.production.importer.ImportObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import de.intranda.goobi.plugins.hotfolder.nli.model.config.HotfolderPluginConfig;
import de.intranda.goobi.plugins.hotfolder.nli.model.data.HotfolderRecord;
import de.intranda.goobi.plugins.hotfolder.nli.model.hotfolder.HotfolderFolder;
import de.intranda.goobi.plugins.hotfolder.nli.model.hotfolder.HotfolderParser;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProviderInterface;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;

public class NLIHotfolderImportTest {

    private final static Path HOTFOLDER_PATH = Path.of("src/test/resources/hotfolder").toAbsolutePath();
    private final static Path CONFIG_PATH = Path.of("src/test/resources/plugin_intranda_administration_hotfolder_nli.xml").toAbsolutePath();
    private final static Path RULESET_PATH = Path.of("src/test/resources/ruleset.xml").toAbsolutePath();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder(new File("src/test").getAbsoluteFile());

    private Path hotfolderPath;
    private Path importPath;
    private HotfolderPluginConfig config;
    private StorageProviderInterface storageProvider;
    private ConfigOpac configOpac;
    private Prefs prefs;

    @Before
    public void setup() throws IOException, ConfigurationException, PreferencesException {

        assertTrue(Files.exists(HOTFOLDER_PATH));
        assertTrue(Files.exists(CONFIG_PATH));
        assertTrue(Files.exists(RULESET_PATH));

        this.hotfolderPath = tempFolder.newFolder("hotfolder").toPath();
        this.importPath = tempFolder.newFolder("import").toPath();
        config = new HotfolderPluginConfig(new XMLConfiguration(CONFIG_PATH.toFile()));
        FileUtils.copyDirectory(HOTFOLDER_PATH.toFile(), hotfolderPath.toFile());
        this.storageProvider = new NIOFileUtils();
        this.prefs = new Prefs();
        prefs.loadPrefs(RULESET_PATH.toString());

        this.configOpac = ConfigOpac.getInstance();

        assertTrue(Files.exists(hotfolderPath));
        assertTrue(prefs != null);

    }

    @Test
    public void testGetHotfolder() throws IOException {
        List<HotfolderFolder> hotfolders = new HotfolderParser(storageProvider).getImportFolders(hotfolderPath, config);
        assertEquals("Wrong number of import folders: " + hotfolders.size(), 2, hotfolders.size());
        HotfolderFolder folder = hotfolders.stream().filter(f -> f.getTemplateName().equals("Audio_and_Video")).findAny().orElse(null);
        assertNotNull(folder);
        assertEquals("Karenp", folder.getOwnerName("997008730630705171"));
        assertEquals("Audio_and_Video", folder.getTemplateName());
        assertEquals("Reuploads-Audio", folder.getProjectFolder().getFileName().toString());
        assertEquals(1, folder.getCurrentProcessFolders(0).size());
        assertEquals("997008730630705171", folder.getCurrentProcessFolders(0).get(0).getFileName().toString());
    }

    @Test
    public void testImport() throws IOException {
        List<HotfolderFolder> hotfolders = new HotfolderParser(storageProvider).getImportFolders(hotfolderPath, config);
        HotfolderFolder hff = hotfolders.stream().filter(f -> f.getTemplateName().equals("Audio_and_Video")).findAny().orElse(null);
        assertNotNull(hff);

        NLIExcelImport excelImport =
                new NLIExcelImport(this.config, this.configOpac, this.storageProvider, this.importPath.toString(), prefs, hff.getTemplateName());
        List<HotfolderRecord> records = excelImport.generateRecordsFromFile(hff);
        assertEquals(1, records.size());

        HotfolderRecord record = records.get(0);
        ImportObject io = excelImport.generateFile(record, hff);
        Path metsPath = Path.of(io.getMetsFilename());
        assertTrue(Files.exists(metsPath));
        Path mediaPath = metsPath.getParent().resolve(metsPath.getFileName().toString().replace(".xml", "")).resolve("images");
        assertTrue(Files.exists(mediaPath));
        assertEquals(1, Files.list(mediaPath).count());
        assertEquals("Reuploads-Audio_997008730630705171", io.getProcessTitle());

    }

}
