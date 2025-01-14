package de.intranda.goobi.plugins.hotfolder.nli.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.io.FileUtils;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.plugin.interfaces.IOpacPlugin;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import de.intranda.goobi.plugins.hotfolder.nli.model.config.HotfolderPluginConfig;
import de.intranda.goobi.plugins.hotfolder.nli.model.data.HotfolderRecord;
import de.intranda.goobi.plugins.hotfolder.nli.model.hotfolder.HotfolderFolder;
import de.intranda.goobi.plugins.hotfolder.nli.model.hotfolder.HotfolderParser;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProviderInterface;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;
import ugh.fileformats.mets.MetsMods;

public class NLIExcelImportTest {

    private final static Path HOTFOLDER_PATH = Path.of("src/test/resources/hotfolder").toAbsolutePath();
    private final static Path CONFIG_PATH = Path.of("src/test/resources/plugin_intranda_administration_hotfolder_nli.xml").toAbsolutePath();
    private final static Path RULESET_PATH = Path.of("src/test/resources/ruleset.xml").toAbsolutePath();
    private final static Path CONFIG_OPAC_PATH = Path.of("src/test/resources/config_opac.xml").toAbsolutePath();
    private final static Path SAMPLE_METS_PATH = Path.of("src/test/resources/meta.xml").toAbsolutePath();

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
    public void test() throws Exception {
        List<HotfolderFolder> hotfolders = new HotfolderParser(storageProvider).getImportFolders(hotfolderPath, config);
        HotfolderFolder hff = hotfolders.stream().filter(f -> f.getTemplateName().equals("templateName")).findAny().orElse(null);
        assertNotNull(hff);

        IOpacPlugin opacPluginMock = Mockito.mock(IOpacPlugin.class);
        ConfigOpacCatalogue cataMock = Mockito.mock(ConfigOpacCatalogue.class);

        MetsMods ff = new MetsMods(prefs);
        ff.read(SAMPLE_METS_PATH.toString());

        Mockito.when(opacPluginMock.search(Mockito.anyString(), Mockito.anyString(), Mockito.any(), Mockito.any())).thenReturn(ff);
        Mockito.when(cataMock.getTitle()).thenReturn("NLI Alma");
        Mockito.when(cataMock.getOpacPlugin()).thenReturn(opacPluginMock);
        ConfigOpac opacMock = Mockito.mock(ConfigOpac.class);
        Mockito.when(opacMock.getAllCatalogues(Mockito.anyString())).thenReturn(List.of(cataMock));

        NLIExcelImport excelImport =
                new NLIExcelImport(this.config, opacMock, this.storageProvider, this.importPath.toString(), prefs, hff.getTemplateName());
        List<HotfolderRecord> records = excelImport.generateRecordsFromFolder(hff);
        assertEquals(6, records.size());

        List<ImportObject> ios = records.stream().map(r -> excelImport.generateFile(r, hff)).collect(Collectors.toList());

        assertEquals(6, ios.size());
        ImportObject io1 = ios.stream().filter(o -> o.getProcessTitle().equals("990037838120205171_26_87-29_03_2024")).findAny().orElse(null);
        ImportObject io2 = ios.stream().filter(o -> o.getProcessTitle().equals("990037838120205171_26_87-29_03_2024-2")).findAny().orElse(null);
        ImportObject io3 = ios.stream().filter(o -> o.getProcessTitle().equals("990037838120205171_2687-29_03_2024-1")).findAny().orElse(null);
        assertNotNull(io1);
        assertNotNull(io2);
        assertNotNull(io3);

        MetsMods ff2 = new MetsMods(prefs);
        ff2.read(io2.getMetsFilename());
        assertEquals("29_03_2024", getMetadataValue(ff2, "PublicationYear"));
        assertEquals("990037838120205171", getMetadataValue(ff2, "CatalogIDDigital"));
        assertEquals("26_87", getMetadataValue(ff2, "shelfmarksource"));
        assertEquals("2", getMetadataValue(ff2, "CurrentNo"));
    }

    public String getMetadataValue(MetsMods ff, String name) {
        String publicationYear =
                ff.getDigitalDocument()
                        .getLogicalDocStruct()
                        .getAllMetadata()
                        .stream()
                        .filter(md -> md.getType().getName().equals(name))
                        .findAny()
                        .map(md -> md.getValue())
                        .orElse("");
        return publicationYear;
    }

}
