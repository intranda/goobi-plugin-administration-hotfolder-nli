package de.intranda.goobi.plugins;

import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.IOException;

import org.goobi.production.importer.Record;
import org.junit.Test;

import de.intranda.goobi.plugins.hotfolder.nli.model.NLIExcelImport;

public class HotfolderNliPluginTest {

    @Test
    public void testVersion() throws IOException {
        String s = "xyz";
        assertNotNull(s);
    }

    //    @Test
    public void test() throws IOException {
        File file = new File("/home/florian/Downloads/Digital_vienna.xlsx");
        NLIExcelImport importer = new NLIExcelImport(null);
        java.util.List<Record> records = importer.generateRecordsFromFile(file);
        System.out.println("Number of records: " + records.size());
    }
}
