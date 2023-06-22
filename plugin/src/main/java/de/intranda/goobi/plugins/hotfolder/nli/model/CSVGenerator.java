package de.intranda.goobi.plugins.hotfolder.nli.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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

    public Path getCSVFilePath() {
        return hotfolderPath.resolve(csvFileName);
    }

    public void generateFile() {
        List<GUIImportResult[]> importResults = getImportResults();

        String header = "time,\tprocess,\tresult\n";
        StringBuilder contentBuilder = new StringBuilder(header);
        for (GUIImportResult[] importResult : importResults) {
            String importResultString = generateImportResultString(importResult);
            contentBuilder.append(importResultString);
        }

        String content = contentBuilder.toString();
        log.debug("content = " + content);

        Path csvPath = getCSVFilePath();
        // TODO: How to use StorageProviderInterface to replace Files in the following case?
        try (OutputStream out = Files.newOutputStream(csvPath, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.CREATE)) {

            out.write(content.getBytes());

        } catch (IOException e) {
            log.error("Error trying to save the csv file: {}", e);
        }
    }


    private String generateImportResultString(GUIImportResult[] importResult) {
        StringBuilder resultBuilder = new StringBuilder();
        for (GUIImportResult result : importResult) {
            resultBuilder.append(generateEntryString(result));
        }

        return resultBuilder.toString();
    }

    private String generateEntryString(GUIImportResult result) {
        StringBuilder entryBuilder = new StringBuilder();
        String timestamp = result.getTimestamp();
        String fileName = result.getImportFileName();
        String errorMessage = result.getErrorMessage();
        // handle blank values
        if (StringUtils.isBlank(timestamp)) {
            timestamp = "unknown";
        }
        if (StringUtils.isBlank(errorMessage)) {
            errorMessage = "ok";
        }
        // timestamp, fileName, errorMessage
        entryBuilder.append(timestamp);
        entryBuilder.append(",\t");
        entryBuilder.append(fileName);
        entryBuilder.append(",\t");
        entryBuilder.append(errorMessage);
        entryBuilder.append("\n");

        return entryBuilder.toString();
    }

    private List<GUIImportResult[]> getImportResults() {
        String resultsString = prepareResultsString();
        if (StringUtils.isBlank(resultsString)) {
            log.debug("There is no log entry found.");
            return null;
        }
        // resultsString has form [{},{}],\n[{},{}],\n[{},{}]
        List<GUIImportResult[]> importResults = new ArrayList<>();
        String[] results = resultsString.split(",\\n");
        for (String result : results) {
            GUIImportResult[] importResult = gson.fromJson(result, GUIImportResult[].class);
            importResults.add(importResult);
        }

        return importResults;
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

        // resultsString has form [[{},{}],\n[{},{}],\n[{},{}]]
        if (StringUtils.isNotBlank(resultsString)) {
            // remove the first [ and the last ]
            resultsString = resultsString.substring(1, resultsString.length() - 1);
        }

        return resultsString;
    }


}
