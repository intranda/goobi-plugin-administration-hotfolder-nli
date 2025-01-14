package de.intranda.goobi.plugins.hotfolder.nli.model;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.goobi.beans.Step;
import org.goobi.production.enums.ImportReturnValue;
import org.goobi.production.enums.LogType;
import org.goobi.production.flow.helper.JobCreation;
import org.goobi.production.importer.ImportObject;

import de.intranda.goobi.plugins.hotfolder.nli.model.config.HotfolderPluginConfig;
import de.intranda.goobi.plugins.hotfolder.nli.model.config.NLIExcelConfig;
import de.intranda.goobi.plugins.hotfolder.nli.model.data.HotfolderRecord;
import de.intranda.goobi.plugins.hotfolder.nli.model.exceptions.ImportException;
import de.intranda.goobi.plugins.hotfolder.nli.model.exceptions.InvalidFolderImportException;
import de.intranda.goobi.plugins.hotfolder.nli.model.exceptions.MissingResourcesImportException;
import de.intranda.goobi.plugins.hotfolder.nli.model.hotfolder.HotfolderFolder;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.HelperSchritte;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import lombok.extern.log4j.Log4j2;
import spark.utils.StringUtils;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.MetadataType;
import ugh.dl.Person;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;

@Log4j2
public class NLIHotfolderImport {

    private final HotfolderPluginConfig pluginConfig;
    private final StorageProviderInterface storageProvider;
    private final String importFolder;
    private final ConfigOpac configOpac;

    public NLIHotfolderImport(HotfolderPluginConfig pluginConfig, StorageProviderInterface storageProvider, String importFolder,
            ConfigOpac configOpac) {
        this.pluginConfig = pluginConfig;
        this.storageProvider = storageProvider;
        this.importFolder = importFolder;
        this.configOpac = configOpac;
    }

    public List<ImportObject> createProcessesFromHotfolder(HotfolderFolder hff) throws IOException, ImportException {
        if (hff.getImportFile() == null) {
            List<Path> processFolders = hff.getCurrentProcessFolders(pluginConfig.getMinutesOfInactivity());
            NLIExcelConfig templateConfig = new NLIExcelConfig(pluginConfig.getTemplateConfig(hff.getTemplateName()));
            if (!templateConfig.isRequireImportFile()) {
                //otherwise:
                log.info("NLI hotfolder - importing without import file");

                NLIExcelImport excelImport = new NLIExcelImport(this.pluginConfig, this.configOpac, this.storageProvider, this.importFolder,
                        loadPrefs(hff.getTemplateName()), hff.getTemplateName());

                // generate the list of all records
                try {
                    List<HotfolderRecord> records = excelImport.generateRecordsFromFolder(hff);
                    // add all ImportObjects regarding the current HotfolderFolder to the list
                    return addImportObjectsRegardingHotfolderFolder(records, hff, excelImport);
                } catch (IOException e) {
                    ImportObject io = new ImportObject();
                    io.setImportFileName(hff.getTemplateName() + "_" + hff.getProjectFolder().getFileName().toString());
                    io.setErrorMessage("Could not read import file");
                    return List.of(io);
                }
            } else {
                log.trace("No importFile: {}, processFolders.size(): {}", hff.getImportFile(), processFolders.size());
                return Collections.emptyList();
            }
        } else {
            //otherwise:
            log.info("NLI hotfolder - importing: " + hff.getImportFile());
            Prefs prefs = loadPrefs(hff.getTemplateName());
            if (prefs == null) {
                throw new MissingResourcesImportException(
                        "Could not load preferences file for template " + hff.getTemplateName() + ". Preferences file may be invalid");
            }
            NLIExcelImport excelImport = new NLIExcelImport(this.pluginConfig, this.configOpac, this.storageProvider, this.importFolder,
                    loadPrefs(hff.getTemplateName()), hff.getTemplateName());

            // generate the list of all records
            try {
                List<HotfolderRecord> records = excelImport.generateRecordsFromFile(hff);
                // add all ImportObjects regarding the current HotfolderFolder to the list
                return addImportObjectsRegardingHotfolderFolder(records, hff, excelImport);
            } catch (IOException e) {
                ImportObject io = new ImportObject();
                io.setImportFileName(hff.getImportFile().getAbsolutePath());
                io.setErrorMessage("Could not read import file");
                return List.of(io);
            }

        }
    }

    private ImportObject prepareImportObject(HotfolderRecord record, HotfolderFolder hff, NLIExcelImport excelImport) {
        ImportObject io = excelImport.generateFile(record, hff);
        if (io == null) {
            return null;
        }

        if (io.getImportReturnValue() == ImportReturnValue.ExportFinished) {
            //create new process
            org.goobi.beans.Process template = ProcessManager.getProcessByExactTitle(hff.getTemplateName());
            org.goobi.beans.Process processNew = JobCreation.generateProcess(io, template);
            if (processNew != null && processNew.getId() != null) {
                log.info("NLI hotfolder - created process: " + processNew.getId());

                // log owner name into process journal and metadata
                logOwnerName(hff, processNew);
                hff.deleteOwnerFile(processNew.getTitel());
                //close first step
                HelperSchritte hs = new HelperSchritte();
                Step firstOpenStep = processNew.getFirstOpenStep();
                hs.CloseStepObjectAutomatic(firstOpenStep);

                // delete source files if configured so
                if (excelImport.shouldDeleteSourceFiles()) {
                    excelImport.deleteSourceFiles(hff, record);
                }

            } else { // processNew == null || processNewId == null
                io.setErrorMessage("Process " + io.getProcessTitle() + " already exists. Aborting import");
                io.setImportReturnValue(ImportReturnValue.NoData);
            }

        } else if (io.getImportReturnValue() == ImportReturnValue.DataAllreadyExists) {
            //record exists and was overwritten. Temp import files have already been deleted. Just delete source folder
            if (excelImport.shouldDeleteSourceFiles()) {
                excelImport.deleteSourceFiles(hff, record);
            }
        } // what about ImportReturnValue.NoData and ImportReturnValue.WriteError? - Zehong

        // delete temporary data anyway, no harm even if there were none
        excelImport.deleteTempImportData(io);

        return io;
    }

    /**
     * Write the owner name taken from the file with .owner extension to process journal and metadata of process
     * 
     * @param hff
     * @param process
     * @return false if no owner file exists
     */
    private boolean logOwnerName(HotfolderFolder hff, org.goobi.beans.Process process) {
        String ownerName = hff.getOwnerName(process.getTitel());
        if (StringUtils.isBlank(ownerName)) {
            return false;
        }

        // ownerName is not blank, log it
        logOwnerNameIntoProcessJournal(process, ownerName);

        // save it into metadata if the ownerType is configured
        if (StringUtils.isNotBlank(this.pluginConfig.getOwnerType())) {
            logOwnerNameIntoMetadata(process, ownerName);
        }
        return true;
    }

    private void logOwnerNameIntoProcessJournal(org.goobi.beans.Process process, String ownerName) {
        String message = "Owner name: " + ownerName;
        Helper.addMessageToProcessJournal(process.getId(), LogType.INFO, message);
    }

    private void logOwnerNameIntoMetadata(org.goobi.beans.Process process, String ownerName) {
        String ownerType = this.pluginConfig.getOwnerType();
        try {
            Fileformat fileformat = process.readMetadataFile();
            Prefs prefs = process.getRegelsatz().getPreferences();

            DigitalDocument digital = fileformat.getDigitalDocument();
            DocStruct logical = digital.getLogicalDocStruct();

            MetadataType mdType = prefs.getMetadataTypeByName(ownerType);
            if (mdType == null) {
                String message = "The configured ownerType " + ownerType + " does not exist. No Metadata will be added.";
                Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, message);
                return;
            }

            if (!mdType.getIsPerson()) {
                String message = "The configured ownerType " + ownerType + " is not a person type. No Metadata will be added.";
                Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, message);
                return;
            }

            log.debug("Adding Metadata " + ownerType + ": " + ownerName);
            Person person = new Person(mdType);
            person.setFirstname(ownerName);
            logical.addPerson(person);

            process.writeMetadataFile(fileformat);

        } catch (ReadException | IOException | SwapException | PreferencesException e) {
            String message = "Failed to read the mets file and get the rulesets.";
            Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, message);

        } catch (MetadataTypeNotAllowedException e) {
            String message = "The configured ownerType " + ownerType + " is not allowed for this publicationType.";
            Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, message);

        } catch (WriteException e) {
            String message = "Failed to save the mets file.";
            Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, message);
        }
    }

    private List<ImportObject> addImportObjectsRegardingHotfolderFolder(List<HotfolderRecord> records, HotfolderFolder hff,
            NLIExcelImport excelImport) {
        int lineNumber = 1;
        List<ImportObject> imports = new ArrayList<>();
        for (HotfolderRecord record : records) {
            lineNumber++;
            ImportObject io = prepareImportObject(record, hff, excelImport);
            if (io != null) {
                imports.add(io);
            }
        }
        return imports;
    }

    private Prefs loadPrefs(String workflowTitle) throws ImportException {
        org.goobi.beans.Process template = ProcessManager.getProcessByTitle(workflowTitle);
        if (template == null) {
            throw new InvalidFolderImportException("Error getting config for template '" + workflowTitle + "'. No such process template found");
        } else if (template.getRegelsatz() == null) {
            throw new MissingResourcesImportException("No ruleset found for template " + template.getTitel());
        }
        return template.getRegelsatz().getPreferences();
    }

}
