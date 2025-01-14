package de.intranda.goobi.plugins.hotfolder.nli.model.config;

import java.nio.file.Path;
import java.util.Map;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.XMLConfiguration;
import org.junit.Test;

import de.intranda.goobi.plugins.hotfolder.nli.model.data.IRecordDataObject;
import de.intranda.goobi.plugins.hotfolder.nli.model.data.RecordDataObject;

public class NLIExcelConfigTest {

    @Test
    public void test_readImportFolder() throws ConfigurationException {
        Path configPath = Path.of("/home/florian/Downloads/plugin_intranda_administration_hotfolder_nli.xml");
        Path importFilePath = Path.of("/home/florian/Downloads/Jpress.xlsx");

        XMLConfiguration xmlConfig = new XMLConfiguration(configPath.toFile());

        HotfolderPluginConfig pluginConfig = new HotfolderPluginConfig(xmlConfig);

        NLIExcelConfig excelConfig = new NLIExcelConfig(pluginConfig.getTemplateConfig("JPress"));

        IRecordDataObject data = new RecordDataObject(Map.of());

        excelConfig.getImportFolder().getValue(data);
    }

}
