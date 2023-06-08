package de.intranda.goobi.plugins.hotfolder.nli.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.input.BOMInputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.poi.ss.usermodel.Cell;
//import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Row.MissingCellPolicy;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.production.enums.ImportReturnValue;
import org.goobi.production.enums.ImportType;
import org.goobi.production.importer.ImportObject;
import org.goobi.production.importer.Record;
import org.goobi.production.plugin.interfaces.IOpacPlugin;

import de.intranda.goobi.plugins.hotfolder.nli.model.exceptions.ImportException;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.ProcessManager;
import de.unigoettingen.sub.search.opac.ConfigOpac;
import de.unigoettingen.sub.search.opac.ConfigOpacCatalogue;
import lombok.Data;
import lombok.extern.log4j.Log4j2;
//import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DigitalDocument;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.Prefs;
import ugh.exceptions.MetadataTypeNotAllowedException;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.TypeNotAllowedForParentException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;

@Log4j2
@Data
//@PluginImplementation
public class NLIExcelImport {

    private Prefs prefs;
    private String importFolder;
    private String data;
    private String currentIdentifier;
    private List<String> currentCollections = new ArrayList<>();
    private String ats;
    private String volumeNumber;
    private String processTitle;

    private boolean replaceExisting = false;
    private boolean moveFiles = false;

    private static String title = "intranda_administration_hotfolder_nli";
    private static StorageProviderInterface storageProvider = StorageProvider.getInstance();

    private static final String ALLOWED_FILE_SUFFIX_PATTERN = ".*\\.(tiff?|pdf|epub|jpe?g)";

    private List<ImportType> importTypes;
    private String workflowTitle;

    private NLIExcelConfig config;
    private Process template;

    // set of folders that contain files of invalid suffices
    private HashSet<Path> dirtyFolderSet = new HashSet<>();
    // set of files that are of invalid suffices
    private HashSet<Path> invalidFileSet = new HashSet<>();

    public NLIExcelImport(HotfolderFolder hff) {

        // by default /opt/digiverso/goobi/tmp/
        importFolder = ConfigurationHelper.getInstance().getTemporaryFolder();
        // prepare import folder if not available yet
        prepareImportFolder(importFolder);

        importTypes = new ArrayList<>();
        importTypes.add(ImportType.FILE);

        if (hff != null) {
            this.workflowTitle = hff.getTemplateName();
        }
    }
    
    public NLIExcelImport(HotfolderFolder hff, NLIExcelConfig config) {
        this(hff);
        this.config = config;
    }

    @SuppressWarnings("unchecked")
    public ImportObject generateFile(String sourceFile, int rowNumber, Record record, HotfolderFolder hff) {
        // reset the template if necessary:
        if (!hff.getTemplateName().equals(workflowTitle)) {
            workflowTitle = hff.getTemplateName();
            config = null;
            getConfig();
        }

        ImportObject io = new ImportObject();
        io.setImportFileName(sourceFile + ":" + rowNumber);
        try {
            // prepare headerOrder and rowMap
            Object tempObject = record.getObject();
            List<Map<?, ?>> tempList = (List<Map<?, ?>>) tempObject;
            Map<String, Integer> headerOrder = (Map<String, Integer>) tempList.get(0);
            Map<Integer, String> rowMap = (Map<Integer, String>) tempList.get(1);

            // import image source folder
            boolean sourceFolderImported = importImageSourceFolder(io, hff, headerOrder, rowMap);
            if (!sourceFolderImported) {
                return null;
            }

            // check mandatory fields
            checkMandatoryFields(headerOrder, rowMap);

            // generate a mets file
            Fileformat ff = createFileformat(io, headerOrder, rowMap);

            // name the process:
            currentIdentifier = getCellValue(config.getProcessHeaderName(), headerOrder, rowMap);
            String processName = hff.getProjectFolder().getFileName() + "_" + currentIdentifier;

            String fileName = nameProcess(processName, io);

            // write mets file into import folder
            ff.write(fileName);

            // copy the image to the import folder, which lies directly in the goobi import directory and contains the files to be imported
            Path importImageFolder = copyImagesFromSourceToTempFolder(io, fileName, hff, headerOrder, rowMap);
            log.debug("importImageFolder = " + importImageFolder);
            if (importImageFolder == null) {
                // there is nothing valid to import
                log.debug("no valid files to import, aborting without creating empty processes");
                io.setErrorMessage("no valid files to import");
                io.setImportReturnValue(ImportReturnValue.NoData);
                return io;
            }

            io.setImportReturnValue(ImportReturnValue.ExportFinished);

            // check if the process exists
            if (replaceExisting) {
                // ImportReturnValue might be changed by the following statement
                replaceExistingProcess(io, ff, importImageFolder, headerOrder, rowMap);
            }

        } catch (ImportException e) {
            log.error(e.toString());
            io.setErrorMessage(e.getMessage());
            io.setImportReturnValue(ImportReturnValue.NoData);
            return io;
        } catch (WriteException | PreferencesException | MetadataTypeNotAllowedException | TypeNotAllowedForParentException | IOException e) {
            log.error(e.toString());
            io.setImportReturnValue(ImportReturnValue.WriteError);
            io.setErrorMessage(e.getMessage());
            return io;
        }

        return io;
    }

    public void deleteSourceFiles(HotfolderFolder hff, Record record) {
        // prepare headerOrder and rowMap
        Object tempObject = record.getObject();
        List<Map<?, ?>> tempList = (List<Map<?, ?>>) tempObject;
        Map<String, Integer> headerOrder = (Map<String, Integer>) tempList.get(0);
        Map<Integer, String> rowMap = (Map<Integer, String>) tempList.get(1);

        Path sourceFolder;
        try {
            sourceFolder = getImageFolderPath(hff, headerOrder, rowMap);
            if (sourceFolder != null && storageProvider.isFileExists(sourceFolder)) {
                // delete all valid files from dirty folders
                if (dirtyFolderSet.contains(sourceFolder)) {
                    for (Path filePath : storageProvider.listFiles(sourceFolder.toString())) {
                        if (!invalidFileSet.contains(filePath)) {
                            storageProvider.deleteFile(filePath);
                        }
                    }
                    if (!storageProvider.listFiles(sourceFolder.toString()).isEmpty()) {
                        // there are still some invalid files in this source folder, do not delete them
                        return;
                    }
                }
                // in any other cases, source folder is not a dirty folder, even if it was
                dirtyFolderSet.remove(sourceFolder);
                // delete the directory, no matter it is empty or not
                storageProvider.deleteDir(sourceFolder);
            }
        } catch (ImportException | IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    public boolean shouldDeleteSourceFiles() {
        return this.getConfig().isMoveImage();
    }

    public void deleteTempImportData(ImportObject io) {
        //remove temp file
        try {
            if (io.getMetsFilename() != null) {
                Path filePath = new File(io.getMetsFilename()).toPath();
                if (storageProvider.isFileExists(filePath)) {
                    storageProvider.deleteFile(filePath);
                }
                Path folderPath = new File(io.getMetsFilename().replace(".xml", "")).toPath();
                if (storageProvider.isFileExists(folderPath)) {
                    storageProvider.deleteDir(folderPath);
                }
            }
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    //    @Override
    //    public List<Record> splitRecords(String records) {
    //        return null;
    //    }

    //    @Override
    public List<Record> generateRecordsFromFile(File file) throws IOException {

        config = null;
        List<Record> recordList = new ArrayList<>();
        String idColumn = getConfig().getIdentifierHeaderName();
        Map<String, Integer> headerOrder = new HashMap<>();

        try (InputStream fileInputStream = new FileInputStream(file);
                BOMInputStream in = new BOMInputStream(fileInputStream, false);
                Workbook wb = WorkbookFactory.create(in)) {

            Sheet sheet = wb.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.rowIterator();

            // get header and data row number from config first
            int rowHeader = getConfig().getRowHeader();
            int rowDataStart = getConfig().getRowDataStart();
            int rowDataEnd = getConfig().getRowDataEnd();
            int rowCounter = 0;

            //  find the header row
            Row headerRow = null;
            while (rowCounter < rowHeader) {
                headerRow = rowIterator.next();
                rowCounter++;
            }

            //  read and validate the header row
            int numberOfCells = headerRow != null ? headerRow.getLastCellNum() : 0;
            for (int i = 0; i < numberOfCells; i++) {
                Cell cell = headerRow.getCell(i);
                if (cell != null) {
                    //                    cell.setCellType(CellType.STRING);
                    String value = cell.getStringCellValue();
                    headerOrder.put(value, i);
                }
            }

            // find out the first data row
            while (rowCounter < rowDataStart - 1) {
                headerRow = rowIterator.next();
                rowCounter++;
            }

            // run through all the data rows
            while (rowIterator.hasNext() && rowCounter < rowDataEnd) {
                rowCounter = addRowProcess(recordList, idColumn, headerOrder, rowIterator, rowCounter);
            }
        } catch (IOException e) {
            log.error(e);
            throw e;
        }

        return recordList;
    }

    //    @Override
    //    public int hashCode(){
    //
    //        //this is a random number, to prevent lombok from calling getConfig every time it wants a hash.
    //        return 4589689;
    //    }

    public NLIExcelConfig getConfig() {
        if (config == null) {
            config = loadConfig(workflowTitle);
            template = ProcessManager.getProcessByTitle(workflowTitle);
            if (template == null) {
                throw new IllegalStateException("Error getting config for template '" + workflowTitle + "'. No such process found");
            } else if (template.getRegelsatz() == null) {
                throw new IllegalStateException("No ruleset found for template " + template.getTitel());
            }
            prefs = template.getRegelsatz().getPreferences();
        }

        return config;
    }

    // ======= private methods ======= //

    /**
     * import the image source folder
     * 
     * @param io ImportObject, which will be modified
     * @param hff HotfolderFolder
     * @param headerOrder map between the header as a string and its order as an integer
     * @param rowMap map between the row number as an integer and the row as a string
     * @return true if the image source folder is successfully imported, false otherwise
     * @throws IOException
     */
    private boolean importImageSourceFolder(ImportObject io, HotfolderFolder hff, Map<String, Integer> headerOrder, Map<Integer, String> rowMap)
            throws IOException {
        Path imageSourceFolder = null;
        try {
            imageSourceFolder = getImageFolderPath(hff, headerOrder, rowMap);
            io.setImportFileName(imageSourceFolder.toString());
            checkImageSourceFolder(imageSourceFolder);
            return true;
        } catch (ImportException e) {
            log.debug("Cannot import " + imageSourceFolder + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Checks that the given folder exists, hasn't been modified within the last N minutes and contains at least one (image) file and no symlinks or
     * folders
     * 
     * @param imageSourceFolder path to the image source folder
     * @throws IOException if an error occurred parsing the given folder
     * @throws ImportException if any of the above conditions are met, meaning that the folder is not ready for import
     */
    private void checkImageSourceFolder(Path imageSourceFolder) throws IOException, ImportException {
        if (!storageProvider.isFileExists(imageSourceFolder)) {
            throw new ImportException("Image folder does not exist");
        }

        // check the last modification time of imageSourceFolder to see if the configured block timeout is over
        Integer minutes = getConfig().getSourceImageFolderMofidicationBlockTimeout();
        Duration blockTimeoutDuration = Duration.of(minutes, ChronoUnit.MINUTES);
        Instant lastModifiedInstant = Instant.ofEpochMilli(storageProvider.getLastModifiedDate(imageSourceFolder));
        if (lastModifiedInstant.isAfter(Instant.now().minus(blockTimeoutDuration))) {
            // comment out the following line or configure the <sourceImageFolderMofidicationBlockTimout> block to allow a fast test
            throw new ImportException("Image folder has been modified in the last " + minutes + " minutes");
        }

        // TODO: How to use StorageProviderInterface to replace Files in the following cases?
        try (Stream<Path> fileStream = Files.list(imageSourceFolder)) {
            List<Path> allFiles = fileStream.collect(Collectors.toList());
            if (allFiles.stream().filter(p -> Files.isRegularFile(p)).findAny().isEmpty()) {
                throw new ImportException("Image folder does not contain any regular files");
            }
            if (!allFiles.stream().allMatch(p -> Files.isRegularFile(p))) {
                throw new ImportException("Image folder contains folders or symlinks");
            }
        }
    }

    /**
     * check if all mandatory fields are correctly set
     * 
     * @param headerOrder map between the header as a string and its order as an integer
     * @param rowMap map between the row number as an integer and the row as a string
     * @throws ImportException
     */
    private void checkMandatoryFields(Map<String, Integer> headerOrder, Map<Integer, String> rowMap) throws ImportException {
        for (MetadataMappingObject mmo : config.getMetadataList()) {
            if (mmo.isMandatory()) {
                Integer col = headerOrder.get(mmo.getHeaderName());
                if (col == null || rowMap.get(col) == null || rowMap.get(col).isEmpty()) {
                    throw new ImportException("Missing column: " + mmo.getHeaderName());
                }
            }
        }
    }

    /**
     * create a Fileformat object
     * 
     * @param io ImportObject, which will be modified
     * @param headerOrder map between the header as a string and its order as an integer
     * @param rowMap map between the row number as an integer and the row as a string
     * @return Fileformat object that is created
     * @throws PreferencesException
     * @throws TypeNotAllowedForParentException
     * @throws MetadataTypeNotAllowedException
     * @throws ImportException
     */
    private Fileformat createFileformat(ImportObject io, Map<String, Integer> headerOrder, Map<Integer, String> rowMap)
            throws PreferencesException, TypeNotAllowedForParentException, MetadataTypeNotAllowedException, ImportException {
        Fileformat ff = initializeFileformat(headerOrder, rowMap);
        DocStruct logical = ff.getDigitalDocument().getLogicalDocStruct();
        DocStruct anchor = null;
        if (Optional.ofNullable(logical).map(DocStruct::getType).map(DocStructType::isAnchor).orElse(false)) {
            anchor = logical;
            logical = anchor.getAllChildren().stream().findFirst().orElse(null);
        }
        writeMetadataToDocStruct(io, logical, anchor, headerOrder, rowMap);
        return ff;
    }

    /**
     * initialize a Fileformat object
     * 
     * @param headerOrder map between the header as a string and its order as an integer
     * @param rowMap map between the row number as an integer and the row as a string
     * @return Fileformat object that is initialized
     * @throws PreferencesException
     * @throws TypeNotAllowedForParentException
     * @throws MetadataTypeNotAllowedException
     * @throws ImportException
     */
    private Fileformat initializeFileformat(Map<String, Integer> headerOrder, Map<Integer, String> rowMap)
            throws PreferencesException, TypeNotAllowedForParentException, MetadataTypeNotAllowedException, ImportException {
        DigitalDocument digitalDocument = null;
        Fileformat ff = null;
        DocStruct logical = null;
        DocStruct anchor = null;
        if (!config.isUseOpac()) {
            ff = new MetsMods(prefs);
            digitalDocument = new DigitalDocument();
            ff.setDigitalDocument(digitalDocument);
            String publicationType = getConfig().getPublicationType();
            DocStructType logicalType = prefs.getDocStrctTypeByName(publicationType);
            logical = digitalDocument.createDocStruct(logicalType);
            digitalDocument.setLogicalDocStruct(logical);
        } else {
            ff = getRecordFromCatalogue(headerOrder, rowMap);
            digitalDocument = ff.getDigitalDocument();
            logical = digitalDocument.getLogicalDocStruct();
            if (logical.getType().isAnchor()) {
                anchor = logical;
                logical = anchor.getAllChildren().get(0);
            }
        }

        DocStructType physicalType = prefs.getDocStrctTypeByName("BoundBook");
        DocStruct physical = digitalDocument.createDocStruct(physicalType);
        digitalDocument.setPhysicalDocStruct(physical);
        Metadata imagePath = new Metadata(prefs.getMetadataTypeByName("pathimagefiles"));
        imagePath.setValue("./images/");
        physical.addMetadata(imagePath);

        // add collections if configured
        String col = getConfig().getCollection();
        if (StringUtils.isNotBlank(col)) {
            Metadata mdColl = new Metadata(prefs.getMetadataTypeByName("singleDigCollection"));
            mdColl.setValue(col);
            logical.addMetadata(mdColl);
        }

        return ff;
    }

    /**
     * get a record from catalogue
     * 
     * @param headerOrder map between the header as a string and its order as an integer
     * @param rowMap map between the row number as an integer and the row as a string
     * @return Fileformat object created from Catalogue
     * @throws ImportException
     */
    private Fileformat getRecordFromCatalogue(Map<String, Integer> headerOrder, Map<Integer, String> rowMap)
            throws ImportException {
        // get catalogue identifier
        String identifier = getCatalogueIdentifierFromRowMap(headerOrder, rowMap);

        // find the proper ConfigOpacCatalogue according to the input catalogue
        String catalogue = config.getOpacName();
        ConfigOpacCatalogue coc = getProperConfigOpacCatalogue(catalogue);
        IOpacPlugin myImportOpac = coc.getOpacPlugin();
        if (myImportOpac == null) {
            throw new ImportException("Opac plugin for catalogue " + catalogue + " not found. Abort.");
        }

        // find out Fileformat from IOpacPlugin regarding identifier
        Fileformat myRdf = getFileformatGivenIdentifier(myImportOpac, coc, identifier);

        // get DocStruct from Fileformat
        DocStruct ds = getDocStructFromFileformat(myRdf, identifier);

        // update the private field ats
        updateFieldAts(myImportOpac, ds);

        return myRdf;
    }

    /**
     * get the catalogue identifier from the row map
     * 
     * @param headerOrder map between the header as a string and its order as an integer
     * @param rowMap map between the row number as an integer and the row as a string
     * @return the catalogue identifier
     * @throws ImportException
     */
    private String getCatalogueIdentifierFromRowMap(Map<String, Integer> headerOrder, Map<Integer, String> rowMap) throws ImportException {
        if (StringUtils.isBlank(config.getIdentifierHeaderName())) {
            throw new ImportException("Cannot request catalogue, no identifier column defined");
        }

        String catalogueIdentifier = rowMap.get(headerOrder.get(config.getIdentifierHeaderName()));
        if (StringUtils.isBlank(catalogueIdentifier)) {
            throw new ImportException("Cannot request catalogue, no identifier in excel file");
        }

        return catalogueIdentifier;
    }

    /**
     * get the config opac catalogue object
     * 
     * @param catalogue title of the aimed ConfigOpacCatalogue object
     * @return the config opac catalogue object if found
     * @throws ImportException if not found
     */
    private ConfigOpacCatalogue getProperConfigOpacCatalogue(String catalogue) throws ImportException {
        ConfigOpacCatalogue coc = null;
        for (ConfigOpacCatalogue configOpacCatalogue : ConfigOpac.getInstance().getAllCatalogues(workflowTitle)) {
            if (configOpacCatalogue.getTitle().equals(catalogue)) {
                coc = configOpacCatalogue;
                // no break here, so by multiple occurrences take the last one? - Zehong
            }
        }

        if (coc == null) {
            throw new ImportException("ConfigOpacCatalogue for catalogue " + catalogue + " not found. Abort.");
        }

        return coc;
    }

    /**
     * 
     * @param myImportOpac IOpacPlugin
     * @param coc ConfigOpacCatalogue object
     * @param identifier
     * @return Fileformat object
     * @throws ImportException
     */
    private Fileformat getFileformatGivenIdentifier(IOpacPlugin myImportOpac, ConfigOpacCatalogue coc, String identifier) throws ImportException {
        Fileformat myRdf = null;
        try {
            myRdf = myImportOpac.search(config.getSearchField(), identifier, coc, prefs);
            if (myRdf == null) {
                throw new ImportException("Could not import record " + identifier
                        + ". Usually this means a ruleset mapping is not correct or the record can not be found in the catalogue.");
            }
        } catch (Exception e1) {
            throw new ImportException("Could not import record " + identifier
                    + ". Usually this means a ruleset mapping is not correct or the record can not be found in the catalogue.");
        }
        return myRdf;
    }

    /**
     * 
     * @param myRdf Fileformat object
     * @param identifier
     * @return DocStruct object
     * @throws ImportException
     */
    private DocStruct getDocStructFromFileformat(Fileformat myRdf, String identifier) throws ImportException {
        DocStruct ds = null;
        try {
            ds = myRdf.getDigitalDocument().getLogicalDocStruct();
            if (ds.getType().isAnchor()) {
                if (ds.getAllChildren() == null || ds.getAllChildren().isEmpty()) {
                    throw new ImportException(
                            "Could not import record " + identifier + ". Found anchor file, but no children. Try to import the child record.");
                }
                ds = ds.getAllChildren().get(0);
            }
        } catch (PreferencesException e1) {
            throw new ImportException("Could not import record " + identifier
                    + ". Usually this means a ruleset mapping is not correct or the record can not be found in the catalogue.");
        }
        return ds;
    }

    /**
     * update the private field ats
     * 
     * @param myImportOpac
     * @param ds DocStruct object
     */
    private void updateFieldAts(IOpacPlugin myImportOpac, DocStruct ds) {
        try {
            ats = myImportOpac.getAtstsl();

            List<? extends Metadata> sort = ds.getAllMetadataByType(prefs.getMetadataTypeByName("CurrentNoSorting"));
            if (sort != null && !sort.isEmpty()) {
                volumeNumber = sort.get(0).getValue();
            }

        } catch (Exception e) {
            ats = "";
        }
    }

    /**
     * 
     * @param io ImportObject, which will be modified
     * @param logical DocStruct
     * @param anchor DocStruct
     * @param headerOrder map between the header as a string and its order as an integer
     * @param rowMap map between the row number as an integer and the row as a string
     */
    private void writeMetadataToDocStruct(ImportObject io, DocStruct logical, DocStruct anchor, Map<String, Integer> headerOrder,
            Map<Integer, String> rowMap) {
        for (MetadataMappingObject mmo : getConfig().getMetadataList()) {
            String identifier = null;
            if (mmo.getNormdataHeaderName() != null) {
                identifier = rowMap.get(headerOrder.get(mmo.getNormdataHeaderName()));
            }

            String value = rowMap.get(headerOrder.get(mmo.getHeaderName()));
            if (StringUtils.isNotBlank(mmo.getRulesetName()) && StringUtils.isNotBlank(value)) {
                // get a list of existing Metadata objects
                List<Metadata> existingMetadata = getExistingMetadata(mmo, logical, anchor);

                if (existingMetadata != null && !existingMetadata.isEmpty()) {
                    existingMetadata.get(0).setValue(value);
                } else {
                    addNewMetadataToDocStruct(mmo, logical, anchor, value, identifier);
                }
            }

            // save process property
            if (StringUtils.isNotBlank(mmo.getPropertyName()) && StringUtils.isNotBlank(value)) {
                Processproperty p = new Processproperty();
                p.setTitel(mmo.getPropertyName());
                p.setWert(value);
                io.getProcessProperties().add(p);
            }
        }
    }

    /**
     * get the list of all existing Metadata objects
     * 
     * @param mmo MetadataMappingObject
     * @param logical DocStruct
     * @param anchor DocStruct
     * @return a list of existing Metadata objects
     */
    private List<Metadata> getExistingMetadata(MetadataMappingObject mmo, DocStruct logical, DocStruct anchor) {
        List<Metadata> existingMetadata =
                (List<Metadata>) logical.getAllMetadataByType(prefs.getMetadataTypeByName(mmo.getRulesetName()));

        if ((existingMetadata == null || existingMetadata.isEmpty()) && anchor != null) {
            existingMetadata = (List<Metadata>) anchor.getAllMetadataByType(prefs.getMetadataTypeByName(mmo.getRulesetName()));
        }

        return existingMetadata;
    }

    /**
     * create a new Metadata object and add it to one of the input DocStruct objects
     * 
     * @param mmo MetadataMappingObject
     * @param logical DocStruct
     * @param anchor DocStruct
     * @param value value of the new Metadata object that should be added
     * @param identifier
     */
    private void addNewMetadataToDocStruct(MetadataMappingObject mmo, DocStruct logical, DocStruct anchor, String value, String identifier) {
        try {
            Metadata md = new Metadata(prefs.getMetadataTypeByName(mmo.getRulesetName()));
            md.setValue(value);

            if (identifier != null) {
                md.setAutorityFile("gnd", "http://d-nb.info/gnd/", identifier);
            }

            if (anchor != null && "anchor".equals(mmo.getDocType())) {
                anchor.addMetadata(md);
            } else {
                logical.addMetadata(md);
            }

        } catch (MetadataTypeNotAllowedException e) {
            log.info(e);
            // Metadata is not known or not allowed
        }
    }

    /**
     * copy Images from source folder to the temp folder
     * 
     * @param io ImportObject, which will be modified
     * @param fileName
     * @param hff HotfolderFolder
     * @param headerOrder map between the header as a string and its order as an integer
     * @param rowMap map between the row number as an integer and the row as a string
     * @return path to the import folder directly within the goobi import directory (? REALLY ?)
     * @throws IOException If an error occured copying source files
     * @throws ImportException If no image folder was found
     */
    private Path copyImagesFromSourceToTempFolder(ImportObject io, String fileName, HotfolderFolder hff, Map<String, Integer> headerOrder,
            Map<Integer, String> rowMap) throws IOException, ImportException {

        Path imageSourceFolder = getImageFolderPath(hff, headerOrder, rowMap);

        if (storageProvider.isFileExists(imageSourceFolder) && storageProvider.isDirectory(imageSourceFolder)) {
            String foldername = fileName.replace(".xml", "");
            String folderNameRule = ConfigurationHelper.getInstance().getProcessImagesMasterDirectoryName();
            folderNameRule = folderNameRule.replace("{processtitle}", io.getProcessTitle());

            Path path = Paths.get(foldername, "images", folderNameRule);
            String fileNamePrefix = getCellValue(config.getImagesHeaderName(), headerOrder, rowMap);
            copyImagesToFolder(imageSourceFolder, path.toString(), fileNamePrefix);
            // check if there are any files copied to path, and if not, return null to signify this
            if (storageProvider.listFiles(path.toString()).isEmpty()) {
                return null;
            }
            return Paths.get(foldername);
        } else {
            throw new ImportException("No images to copy: Image source folder " + imageSourceFolder + " does not exist");
        }
    }

    /**
     * 
     * @param hff HotfolderFolder
     * @param headerOrder map between the header as a string and its order as an integer
     * @param rowMap map between the row number as an integer and the row as a string
     * @return Path to the image folder
     * @throws ImportException if no image folder is set in the excel file
     */
    private Path getImageFolderPath(HotfolderFolder hff, Map<String, Integer> headerOrder, Map<Integer, String> rowMap) throws ImportException {
        String imageFolder = getCellValue(config.getProcessHeaderName(), headerOrder, rowMap);
        if (StringUtils.isBlank(imageFolder)) {
            throw new ImportException("No imageFolder in excel File");
        }

        return Paths.get(hff.getProjectFolder().toString(), imageFolder);
    }

    /**
     * 
     * @param column
     * @param headerOrder map between the header as a string and its order as an integer
     * @param rowMap map between the row number as an integer and the row as a string
     * @return the value of the cell
     */
    private String getCellValue(String column, Map<String, Integer> headerOrder, Map<Integer, String> rowMap) {
        return rowMap.get(headerOrder.get(column));
    }

    /**
     * replace an existing process with current settings
     * 
     * @param io ImportObject, which will be modified
     * @param ff Fileformat
     * @param importFolder path to the import folder
     * @param headerOrder map between the header as a string and its order as an integer
     * @param rowMap map between the row number as an integer and the row as a string
     */
    private void replaceExistingProcess(ImportObject io, Fileformat ff, Path importFolder, Map<String, Integer> headerOrder,
            Map<Integer, String> rowMap) {

        Process existingProcess = ProcessManager.getProcessByExactTitle(io.getProcessTitle());
        if (existingProcess == null) {
            return;
        }

        // otherwise, try to replace the existing process
        try {
            String fileNamePrefix = getCellValue(config.getImagesHeaderName(), headerOrder, rowMap);
            writeToExistingProcess(ff, importFolder, existingProcess, fileNamePrefix);
            io.setErrorMessage("Process name already exists. Replaced data in pocess " + existingProcess.getTitel());
            io.setImportReturnValue(ImportReturnValue.DataAllreadyExists);
        } catch (ImportException e) {
            log.error(e);
            io.setErrorMessage(e.getMessage());
            io.setImportReturnValue(ImportReturnValue.NoData);
        } finally {
            deleteTempImportData(io);
        }
    }

    /**
     * write information to an existing Goobi process
     * 
     * @param ff Fileformat
     * @param importFolder path to the import folder
     * @param existingProcess Goobi process
     * @param fileNamePrefix prefix of file names
     * @throws ImportException
     */
    private void writeToExistingProcess(Fileformat ff, Path importFolder, Process existingProcess, String fileNamePrefix)
            throws ImportException {
        try {
            existingProcess.writeMetadataFile(ff);
            copyImagesIntoProcessFolder(existingProcess, importFolder, fileNamePrefix);

        } catch (WriteException | PreferencesException | IOException | SwapException e) {
            throw new ImportException(e.getMessage(), e);
        }
    }

    /**
     * copy images into the process folder
     * 
     * @param existingProcess Goobi process
     * @param sourceRootFolder path to the source root folder
     * @param fileNamePrefix prefix of the file names
     * @throws ImportException
     */
    private void copyImagesIntoProcessFolder(Process existingProcess, Path sourceRootFolder, String fileNamePrefix) throws ImportException {
        if (storageProvider.isFileExists(sourceRootFolder)) {
            Path sourceImageFolder = Paths.get(sourceRootFolder.toString(), "images");
            Path sourceOcrFolder = Paths.get(sourceRootFolder.toString(), "ocr");

            if (storageProvider.isDirectory(sourceImageFolder)) {
                try {
                    String copyToDirectory = existingProcess.getImagesDirectory();
                    copyImagesToFolder(sourceImageFolder, copyToDirectory, fileNamePrefix);

                } catch (IOException | SwapException e) {
                    throw new ImportException(e.getMessage(), e);
                }
            }

            // ocr
            if (storageProvider.isFileExists(sourceOcrFolder) && storageProvider.isDirectory(sourceOcrFolder)) {
                List<Path> dataInSourceOcrFolder = storageProvider.listFiles(sourceOcrFolder.toString());
                for (Path currentData : dataInSourceOcrFolder) {
                    copyOcrFile(currentData, existingProcess);
                }
            }
        }
    }

    /**
     * copy images between folders
     * 
     * @param sourceImageFolder The folder containing the data for copy. Both subfolders and files with a .tif, .pdf or .epux suffix are being copied
     * @param copyToDirectory The directory into which the files/subdirectories are to be copied
     * @param fileNamePrefix A prefix for the file names of .tif files in the 'copyToDirectory'. If fileNamePrefix is blank, the image files are
     *            copied without name change. Otherwise they are named <fileNamePrefix>_i.tif/pdf/epub in the target folder, where i is an
     *            incrementing integer starting at value 1
     * @throws IOException
     */
    private void copyImagesToFolder(Path sourceImageFolder, String copyToDirectory, String fileNamePrefix) throws IOException {

        List<Path> dataInSourceImageFolder = storageProvider.listFiles(sourceImageFolder.toString());
        dataInSourceImageFolder.sort(Comparator.comparing(o -> o.toFile().getName().toUpperCase()));
        storageProvider.createDirectories(Paths.get(copyToDirectory));

        int iNumber = 1;
        for (Path currentData : dataInSourceImageFolder) {
            String fileName = currentData.getFileName().toString();

            if (storageProvider.isDirectory(currentData)) {
                Path targetDir = Paths.get(copyToDirectory).resolve(currentData.getFileName());
                storageProvider.createDirectories(targetDir);
                storageProvider.copyDirectory(currentData, targetDir);
            } else if (!fileName.startsWith(".") && fileName.toLowerCase().matches(ALLOWED_FILE_SUFFIX_PATTERN)) {
                String newFilename = fileName;
                if (StringUtils.isNotBlank(fileNamePrefix)) {
                    String number = String.format("%04d", iNumber);
                    newFilename = fileNamePrefix + "_" + number + "." + FilenameUtils.getExtension(currentData.toString());
                }
                iNumber++;
                storageProvider.copyFile(currentData, Paths.get(copyToDirectory, newFilename));
            } else { // if files do not have allowed suffices, then try to report this instead of making empty processes
                String suffix = fileName.substring(fileName.lastIndexOf(".") + 1);
                log.debug("The file named '{}' has an invalid suffix: {} ", fileName, suffix);
                dirtyFolderSet.add(sourceImageFolder);
                invalidFileSet.add(currentData);
            }
        }
    }

    /**
     * copy ocr files
     * 
     * @param currentData path to the current file
     * @param existingProcess Goobi process
     */
    private void copyOcrFile(Path currentData, Process existingProcess) {
        try {
            // TODO: What is a proper candidate in StorageProviderInterface to replace Files::isRegularFile?
            if (Files.isRegularFile(currentData)) {
                copyFile(currentData, Paths.get(existingProcess.getOcrDirectory(), currentData.getFileName().toString()));
            } else {
                // TODO: Should we replace the use of FileUtils with StorageProvider? 
                // The method copyDirectory provided by FileUtils preserves the file dates, which might be important here.
                FileUtils.copyDirectory(currentData.toFile(), Paths.get(existingProcess.getOcrDirectory()).toFile());
            }
        } catch (IOException | SwapException e) {
            log.error(e);
        }
    }

    /**
     * copy file
     * 
     * @param file path to the file
     * @param destination path to the destination
     * @throws IOException
     */
    private void copyFile(Path file, Path destination) throws IOException {

        if (moveFiles) {
            storageProvider.move(file, destination);
            //            Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING);
        } else if (storageProvider.isDirectory(file)) {
            storageProvider.copyDirectory(file, destination);
        } else {
            storageProvider.copyFile(file, destination);
        }
            //            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);

    }

    /**
     * name a Goobi process
     * 
     * @param processTitle title of the process
     * @param io ImportObject, which will be modified
     * @return path to the Mets file as a string
     */
    private String nameProcess(String processTitle, ImportObject io) {
        // set new process title
        String fileName = importFolder + File.separator + processTitle + ".xml";
        io.setProcessTitle(processTitle);
        io.setMetsFilename(fileName);
        return fileName;
    }

    /**
     * 
     * @param recordList a list of Record objects
     * @param idColumn
     * @param headerOrder map between the header as a string and its order as an integer
     * @param rowIterator
     * @param rowCounter
     * @return rowCounter
     */
    private int addRowProcess(List<Record> recordList, String idColumn, Map<String, Integer> headerOrder, Iterator<Row> rowIterator, int rowCounter) {

        Map<Integer, String> map = new HashMap<>();
        Row row = rowIterator.next();
        rowCounter++;
        int lastColumn = row.getLastCellNum();
        if (lastColumn == -1) {
            return rowCounter;
        }
        for (int cn = 0; cn < lastColumn; cn++) {
            Cell cell = row.getCell(cn, MissingCellPolicy.CREATE_NULL_AS_BLANK);
            String value = "";
            switch (cell.getCellType()) {
                case BOOLEAN:
                    value = cell.getBooleanCellValue() ? "true" : "false";
                    break;
                case FORMULA:
                    value = cell.getRichStringCellValue().getString();
                    break;
                case NUMERIC:
                    value = String.valueOf((long) cell.getNumericCellValue());
                    break;
                case STRING:
                    value = cell.getStringCellValue();
                    break;
                default:
                    // none, error, blank
                    value = "";
                    break;
            }
            map.put(cn, value);

        }

        for (String v : map.values()) {
            if (v != null && !v.isEmpty()) {
                Record r = new Record();
                r.setId(map.get(headerOrder.get(idColumn)));
                List<Map<?, ?>> list = new ArrayList<>();
                list.add(headerOrder);
                list.add(map);

                r.setObject(list);
                recordList.add(r);
                break;
            }
        }
        return rowCounter;
    }

    /**
     * prepare the import folder
     * 
     * @param folderPathString path to the import folder as a string
     */
    private void prepareImportFolder(String folderPathString) {
        log.debug("perparing import folder: " + folderPathString);
        Path folderPath = Path.of(folderPathString);
        if (!storageProvider.isFileExists(folderPath)) {
            try {
                storageProvider.createDirectories(folderPath);
            } catch (IOException e) {
                log.error("Errors happended trying to create the folder " + folderPathString);
            }
        }
    }

    /**
     * Loads the configuration for the selected template or the default configuration, if the template was not specified.
     * 
     * The configuration is stored in a {@link ExcelConfig} object
     * 
     * @param workflowTitle
     * @return
     */
    private NLIExcelConfig loadConfig(String workflowTitle) {

        //        //TODO: this is for testing only
        //        XMLConfiguration xmlConfig = new XMLConfiguration();
        //        xmlConfig.setDelimiterParsingDisabled(true);
        //        try {
        //            xmlConfig.load("/opt/digiverso/goobi/config/plugin_intranda_administration_hotfolder_nli.xml");
        //        } catch (ConfigurationException e1) {
        //            // TODO Auto-generated catch block
        //            e1.printStackTrace();
        //        }

        SubnodeConfiguration myconfig = getTemplateConfig(workflowTitle);

        if (myconfig != null) {
            replaceExisting = myconfig.getBoolean("replaceExistingProcesses", false);
            moveFiles = myconfig.getBoolean("moveFiles", false);
        }

        return new NLIExcelConfig(myconfig);
    }

    /**
     * 
     * @param workflowTitle
     * @return
     */
    public static SubnodeConfiguration getTemplateConfig(String workflowTitle) {
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(title);

        xmlConfig.setExpressionEngine(new XPathExpressionEngine());
        xmlConfig.setReloadingStrategy(new FileChangedReloadingStrategy());

        SubnodeConfiguration myconfig = null;
        try {
            myconfig = xmlConfig.configurationAt("//config[./template = '" + workflowTitle + "']");
        } catch (IllegalArgumentException e) {
            myconfig = xmlConfig.configurationAt("//config[./template = '*']");
        }

        return myconfig;
    }

}
