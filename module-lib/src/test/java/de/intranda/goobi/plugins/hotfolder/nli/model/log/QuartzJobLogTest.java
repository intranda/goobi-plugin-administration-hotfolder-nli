package de.intranda.goobi.plugins.hotfolder.nli.model.log;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class QuartzJobLogTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder(new File("src/test").getAbsoluteFile());

    private Path hotfolderPath;

    @Before
    public void setup() throws Exception {
        hotfolderPath = tempFolder.getRoot().toPath();
        resetSingleton();
    }

    // the QuartzJobLog instance is a static singleton, reset it so every test starts clean
    private void resetSingleton() throws Exception {
        Field field = QuartzJobLog.class.getDeclaredField("logInstance");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    public void addErrorEntryWithExceptionHavingNoMessageDoesNotWriteLiteralNullToCsv() throws Exception {
        QuartzJobLog quartzJobLog = QuartzJobLog.getInstance(hotfolderPath);

        quartzJobLog.addErrorEntry("importing folder xyz", new NullPointerException());
        quartzJobLog.generateQuartzErrorsLogFile();

        String csvContent = Files.readString(quartzJobLog.getErrorsFilePath());
        String[] lines = csvContent.split("\n");
        assertEquals(2, lines.length);
        String errorLine = lines[1];
        assertFalse("CSV error entry must not be the literal string 'null'", errorLine.trim().endsWith(",null"));
        assertTrue(errorLine.contains("NullPointerException"));
        assertTrue(errorLine.contains("importing folder xyz"));
    }

    @Test
    public void generateQuartzErrorsJsonLogFileWritesStackTraceAndContext() throws Exception {
        QuartzJobLog quartzJobLog = QuartzJobLog.getInstance(hotfolderPath);
        Exception cause = new IllegalStateException("boom");

        quartzJobLog.addErrorEntry("Hotfolder for template (folder123)", cause);
        quartzJobLog.generateQuartzErrorsJsonLogFile();

        Path jsonPath = quartzJobLog.getErrorsJsonFilePath();
        assertTrue(Files.exists(jsonPath));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(jsonPath.toFile());
        assertTrue(root.isArray());
        assertEquals(1, root.size());

        JsonNode entry = root.get(0);
        assertEquals(hotfolderPath.toString(), entry.get("hotfolder").asText());
        assertEquals("Hotfolder for template (folder123)", entry.get("context").asText());
        assertEquals("boom", entry.get("message").asText());
        assertTrue(entry.get("errorType").asText().endsWith("IllegalStateException"));
        String stackTrace = entry.get("stackTrace").asText();
        assertTrue(stackTrace.contains("IllegalStateException: boom"));
        assertTrue(stackTrace.contains(QuartzJobLogTest.class.getSimpleName()));
    }
}
