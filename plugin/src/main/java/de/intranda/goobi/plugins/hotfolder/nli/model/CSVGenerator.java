package de.intranda.goobi.plugins.hotfolder.nli.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

import com.google.gson.Gson;

import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import lombok.extern.log4j.Log4j2;
import spark.utils.StringUtils;

@Log4j2
public class CSVGenerator {
    private static final DateTimeFormatter formatter = GUIImportResult.getFormatter();
    private static final StorageProviderInterface storageProvider = StorageProvider.getInstance();
    private static final Gson gson = new Gson();
    private String jsonFileName;
    private String csvFileName;
    private Path hotfolderPath;

    public CSVGenerator(Path hotfolderPath, String jsonFileName) {
        this.hotfolderPath = hotfolderPath;
        this.jsonFileName = jsonFileName;
        this.csvFileName = createCSVFileName();
    }

    private String createCSVFileName() {
        String csvName = jsonFileName.replaceAll("\\..*$", ".csv");
        log.debug("csvName = " + csvName);
        return csvName;
    }

    public void generateFile() {
        GUIImportResult[] importResults = getImportResults();

        String header = "time,\tprocess,\tresult\n";
        StringBuilder contentBuilder = new StringBuilder(header);
        for (GUIImportResult result : importResults) {
            String entry = generateEntryString(result);
            contentBuilder.append(entry);
        }

        String content = contentBuilder.toString();
        log.debug("content = " + content);

    }

    private String generateEntryString(GUIImportResult result) {
        StringBuilder entryBuilder = new StringBuilder();
        entryBuilder.append(result.getTimestamp());
        entryBuilder.append(",\t");
        entryBuilder.append(result.getImportFileName());
        entryBuilder.append(",\t");
        entryBuilder.append(result.getErrorMessage());
        entryBuilder.append("\n");

        return entryBuilder.toString();
    }

    private GUIImportResult[] getImportResults() {
        String resultsString = prepareResultsString();
        if (StringUtils.isBlank(resultsString)) {
            log.debug("There is no log entry found.");
            return null;
        }

        return null;
    }

    private String prepareResultsString() {
        String resultsString = "";
        Path resultsJsonPath = hotfolderPath.resolve(jsonFileName);
        try (InputStream inputStream = storageProvider.newInputStream(resultsJsonPath)) {
            resultsString = new String(inputStream.readAllBytes());
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        if (StringUtils.isNotBlank(resultsString)) {
            // remove the first [ and the last ]
            resultsString = resultsString.substring(1, resultsString.length() - 1);
        }

        return resultsString;
    }


}
