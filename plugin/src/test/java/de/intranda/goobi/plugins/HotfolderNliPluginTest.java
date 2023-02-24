package de.intranda.goobi.plugins;

import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.IOException;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.goobi.production.importer.Record;
import org.junit.Before;
import org.junit.Test;

import de.intranda.goobi.plugins.hotfolder.nli.model.NLIExcelConfig;
import de.intranda.goobi.plugins.hotfolder.nli.model.NLIExcelImport;
import de.sub.goobi.config.ConfigurationHelper;

public class HotfolderNliPluginTest {

    private static final String configPath = "src/test/resources/config/plugin_intranda_administration_hotfolder_nli.xml";
    private NLIExcelConfig config;
    
    
    @Before
    public void setup() throws ConfigurationException {
        ConfigurationHelper.CONFIG_FILE_NAME = "./src/test/resources/goobi_config.properties";
        XMLConfiguration xmlConfig = new XMLConfiguration(new File(configPath));
        SubnodeConfiguration subConfig = xmlConfig.configurationAt("config");
        config = new NLIExcelConfig(subConfig);
    }
    
//    @Test
    public void testVersion() throws IOException {
        String s = "xyz";
        assertNotNull(s);
    }

//    @Test
    public void test() throws IOException {
        File file = new File("/home/florian/Downloads/Digital_vienna.xlsx");
        NLIExcelImport importer = new NLIExcelImport(null, this.config);
        java.util.List<Record> records = importer.generateRecordsFromFile(file);
        System.out.println("Number of records: " + records.size());
    }
}
