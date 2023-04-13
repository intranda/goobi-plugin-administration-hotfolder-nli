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

    private List<ImportType> importTypes;
    private String workflowTitle;

    private NLIExcelConfig config;
    private Process template;

    public NLIExcelImport(HotfolderFolder hff) {

        importFolder = ConfigurationHelper.getInstance().getTemporaryFolder();

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

        //reset the template if necessary:
        if (hff.getTemplateName() != workflowTitle) {
            workflowTitle = hff.getTemplateName();
            config = null;
            getConfig();
        }

        ImportObject io = new ImportObject();
        io.setImportFileName(sourceFile + ":" + rowNumber);
        Fileformat ff;
        try {

            try {
                Path imageSourceFolder = getImageFolderPath(record, hff);
                io.setImportFileName(imageSourceFolder.toString());
                try {
                    checkImageSourceFolder(imageSourceFolder);
                } catch (ImportException e) {
                    log.debug("Cannot import " + imageSourceFolder + ": " + e.getMessage());
                    return null;
                }
                checkMandatoryFields(getHeaderOrder(record), getRowMap(record));
            } catch (ImportException e) {
                io.setErrorMessage(e.getMessage());
                io.setImportReturnValue(ImportReturnValue.NoData);
                return io;
            } catch (Exception e) {
                log.error(e);
                io.setErrorMessage(e.getMessage());
                io.setImportReturnValue(ImportReturnValue.NoData);
                return io;
            }

            //Folder directly within the goobi import directory containing the files to be imported
            Path importFolder = null;
            // generate a mets file
            try {
                ff = createFileformat(io, getHeaderOrder(record), getRowMap(record));
                //Name the process:
                currentIdentifier = getCellValue(config.getProcessHeaderName(), record);
                String processName = hff.getProjectFolder().getFileName() + "_" + currentIdentifier;

                String fileName = nameProcess(processName, io);
                // write mets file into import folder
                ff.write(fileName);

                //                this.currentIdentifier = getCellValue(config.getImagesHeaderName(), record);
                //                if (this.currentIdentifier == null || this.currentIdentifier.isEmpty()) {
                //                    this.currentIdentifier = io.getProcessTitle();
                //                }

                //copy the image to the import folder
                importFolder = copyImagesFromSourceToTempFolder(io, record, fileName, hff, getCellValue(config.getImagesHeaderName(), record));

            } catch (ImportException e1) {
                log.error(e1.toString());
                io.setErrorMessage(e1.getMessage());
                io.setImportReturnValue(ImportReturnValue.NoData);
                return io;
            }

            io.setImportReturnValue(ImportReturnValue.ExportFinished);

            // check if the process exists
            if (replaceExisting) {
                Process existingProcess = ProcessManager.getProcessByExactTitle(io.getProcessTitle());
                if (existingProcess != null) {
                    try {
                        writeToExistingProcess(io, ff, importFolder, existingProcess, getCellValue(config.getImagesHeaderName(), record));
                        io.setErrorMessage("Process name already exists. Replaced data in pocess " + existingProcess.getTitel());
                        io.setImportReturnValue(ImportReturnValue.DataAllreadyExists);
                        return io;
                    } catch (ImportException e) {
                        log.error(e);
                        io.setErrorMessage(e.getMessage());
                        io.setImportReturnValue(ImportReturnValue.NoData);
                        return io;
                    } finally {
                        deleteTempImportData(io);
                    }
                }
            }

        } catch (WriteException | PreferencesException | MetadataTypeNotAllowedException | TypeNotAllowedForParentException | IOException e) {
            io.setImportReturnValue(ImportReturnValue.WriteError);
            io.setErrorMessage(e.getMessage());
            return io;
        }
        return io;
    }

    public void deleteSourceFiles(HotfolderFolder hff, Record record) {
        Path sourceFolder;
        try {
            sourceFolder = getImageFolderPath(record, hff);
            if (sourceFolder != null && Files.exists(sourceFolder)) {
                StorageProvider.getInstance().deleteDir(sourceFolder);
            }
        } catch (ImportException e) {
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
                File file = new File(io.getMetsFilename());
                if (file.exists()) {
                    StorageProvider.getInstance().deleteFile(file.toPath());
                }
                File folder = new File(io.getMetsFilename().replace(".xml", ""));
                if (folder.exists()) {
                    StorageProvider.getInstance().deleteDir(folder.toPath());
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

        InputStream fileInputStream = null;
        try {
            fileInputStream = new FileInputStream(file);
            BOMInputStream in = new BOMInputStream(fileInputStream, false);
            Workbook wb = WorkbookFactory.create(in);
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
            int numberOfCells = headerRow.getLastCellNum();
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
        } finally {
            if (fileInputStream != null) {
                try {
                    fileInputStream.close();
                } catch (IOException e) {
                    log.error(e);
                }
            }
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

    private Fileformat createFileformat(ImportObject io, Map<String, Integer> headerOrder, Map<Integer, String> rowMap)
            throws PreferencesException, TypeNotAllowedForParentException, MetadataTypeNotAllowedException, ImportException {
        Fileformat ff;
        ff = initializeFileformat(io, headerOrder, rowMap);
        DocStruct logical = ff.getDigitalDocument().getLogicalDocStruct();
        DocStruct anchor = null;
        if (Optional.ofNullable(logical).map(DocStruct::getType).map(DocStructType::isAnchor).orElse(false)) {
            anchor = logical;
            logical = anchor.getAllChildren().stream().findFirst().orElse(null);
        }
        writeMetadataToDocStruct(io, headerOrder, rowMap, logical, anchor);
        return ff;
    }

    private Fileformat initializeFileformat(ImportObject io, Map<String, Integer> headerOrder, Map<Integer, String> rowMap)
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
            boolean validRequest = false;
            for (MetadataMappingObject mmo : config.getMetadataList()) {
                if (StringUtils.isNotBlank(mmo.getSearchField()) && headerOrder.get(mmo.getHeaderName()) != null) {
                    validRequest = true;
                    break;
                }
            }

            if (!validRequest) {
                if (StringUtils.isBlank(config.getIdentifierHeaderName())) {
                    throw new ImportException("Cannot request catalogue, no identifier column defined");
                }

                String catalogueIdentifier = rowMap.get(headerOrder.get(config.getIdentifierHeaderName()));
                if (StringUtils.isBlank(catalogueIdentifier)) {
                    throw new ImportException("Cannot request catalogue, no identifier in excel file");
                }
            }

            String catalogue = config.getOpacName();
            ff = getRecordFromCatalogue(rowMap, headerOrder, catalogue);
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

    private Fileformat getRecordFromCatalogue(Map<Integer, String> rowMap, Map<String, Integer> headerOrder, String catalogue)
            throws ImportException {
        // find the proper ConfigOpacCatalogue according to the input catalogue
        ConfigOpacCatalogue coc = getProperConfigOpacCatalogue(catalogue);
        IOpacPlugin myImportOpac = coc.getOpacPlugin();
        if (myImportOpac == null) {
            throw new ImportException("Opac plugin for catalogue " + catalogue + " not found. Abort.");
        }

        String identifier = rowMap.get(headerOrder.get(config.getIdentifierHeaderName()));

        // find out Fileformat from IOpacPlugin regarding identifier
        Fileformat myRdf = getFileformatGivenIdentifier(myImportOpac, coc, identifier);

        // get DocStruct from Fileformat
        DocStruct ds = getDocStructFromFileformat(myRdf, identifier);

        // update the private field ats
        updateFieldAts(myImportOpac, ds);

        return myRdf;
    }

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

    private void writeMetadataToDocStruct(ImportObject io, Map<String, Integer> headerOrder, Map<Integer, String> rowMap, DocStruct logical,
            DocStruct anchor) {
        for (MetadataMappingObject mmo : getConfig().getMetadataList()) {

            String value = rowMap.get(headerOrder.get(mmo.getHeaderName()));
            String identifier = null;
            if (mmo.getNormdataHeaderName() != null) {
                identifier = rowMap.get(headerOrder.get(mmo.getNormdataHeaderName()));
            }
            if (StringUtils.isNotBlank(mmo.getRulesetName()) && StringUtils.isNotBlank(value)) {
                try {
                    List<Metadata> existingMetadata =
                            (List<Metadata>) logical.getAllMetadataByType(prefs.getMetadataTypeByName(mmo.getRulesetName()));
                    if ((existingMetadata == null || existingMetadata.isEmpty()) && anchor != null) {
                        existingMetadata = (List<Metadata>) anchor.getAllMetadataByType(prefs.getMetadataTypeByName(mmo.getRulesetName()));
                    }
                    if (existingMetadata != null && !existingMetadata.isEmpty()) {
                        existingMetadata.get(0).setValue(value);
                    } else {
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
                    }
                } catch (MetadataTypeNotAllowedException e) {
                    log.info(e);
                    // Metadata is not known or not allowed
                }
            }

            if (StringUtils.isNotBlank(mmo.getPropertyName()) && StringUtils.isNotBlank(value)) {
                Processproperty p = new Processproperty();
                p.setTitel(mmo.getPropertyName());
                p.setWert(value);
                io.getProcessProperties().add(p);
            }
        }
    }

    /**
     * 
     * @param io
     * @param record
     * @param fileName
     * @param hff
     * @return path to the import folder directly within the goobi import directory
     * @throws IOException If an error occured copying source files
     * @throws ImportException If no image folder was found
     */
    private Path copyImagesFromSourceToTempFolder(ImportObject io, Record record, String fileName, HotfolderFolder hff, String filenamePrefix)
            throws IOException, ImportException {

        Path imageSourceFolder = getImageFolderPath(record, hff);

        if (Files.exists(imageSourceFolder) && Files.isDirectory(imageSourceFolder)) {

            String foldername = fileName.replace(".xml", "");
            String folderNameRule = ConfigurationHelper.getInstance().getProcessImagesMasterDirectoryName();
            folderNameRule = folderNameRule.replace("{processtitle}", io.getProcessTitle());

            Path path = Paths.get(foldername, "images", folderNameRule);
            copyImagesToFolder(imageSourceFolder, path.toString(), filenamePrefix);
            return Paths.get(foldername);
        } else {
            throw new ImportException("No images to copy: Image source folder " + imageSourceFolder + " does not exist");
        }
    }

    private Path getImageFolderPath(Record record, HotfolderFolder hff) throws ImportException {
        String imageFolder = getCellValue(config.getProcessHeaderName(), record);
        if (StringUtils.isBlank(imageFolder)) {
            throw new ImportException("No imageFolder in excel File");
        }
        Path imageSourceFolder = Paths.get(hff.getProjectFolder().toString(), imageFolder);
        return imageSourceFolder;
    }

    private String getCellValue(String column, Record record) {
        Object tempObject = record.getObject();

        List<Map<?, ?>> list = (List<Map<?, ?>>) tempObject;
        Map<String, Integer> headerOrder = (Map<String, Integer>) list.get(0);
        Map<Integer, String> rowMap = (Map<Integer, String>) list.get(1);
        return rowMap.get(headerOrder.get(column));
    }

    private void writeToExistingProcess(ImportObject io, Fileformat ff, Path importFolder, Process existingProcess, String filenamePrefix)
            throws ImportException {
        try {
            existingProcess.writeMetadataFile(ff);
            copyImagesIntoProcessFolder(existingProcess, importFolder, filenamePrefix);
        } catch (WriteException | PreferencesException | IOException | SwapException e) {
            throw new ImportException(e.getMessage(), e);

        }
    }

    private void copyImagesIntoProcessFolder(Process existingProcess, Path sourceRootFolder, String filenamePrefix) throws ImportException {
        if (StorageProvider.getInstance().isFileExists(sourceRootFolder)) {
            Path sourceImageFolder = Paths.get(sourceRootFolder.toString(), "images");
            Path sourceOcrFolder = Paths.get(sourceRootFolder.toString(), "ocr");

            if (StorageProvider.getInstance().isDirectory(sourceImageFolder)) {
                try {
                    String copyToDirectory = existingProcess.getImagesDirectory();

                    copyImagesToFolder(sourceImageFolder, copyToDirectory, filenamePrefix);
                } catch (IOException | SwapException e) {
                    throw new ImportException(e.getMessage(), e);
                }
            }

            // ocr
            if (Files.exists(sourceOcrFolder)) {
                List<Path> dataInSourceImageFolder = StorageProvider.getInstance().listFiles(sourceOcrFolder.toString());
                for (Path currentData : dataInSourceImageFolder) {
                    if (Files.isRegularFile(currentData)) {
                        try {
                            copyFile(currentData, Paths.get(existingProcess.getOcrDirectory(), currentData.getFileName().toString()));
                        } catch (IOException | SwapException e) {
                            log.error(e);
                        }
                    } else {
                        try {
                            FileUtils.copyDirectory(currentData.toFile(), Paths.get(existingProcess.getOcrDirectory()).toFile());
                        } catch (IOException | SwapException e) {
                            log.error(e);
                        }
                    }
                }
            }
        }
    }

    /**
     * 
     * @param sourceImageFolder The folder containing the data for copy. Both subfolders and files with a .tif, .pdf or .epux suffix are being copied
     * @param copyToDirectory The directory into which the files/subdirectories are to be copied
     * @param filenamePrefix A prefix for the file names of .tif files in the 'copyToDirectory'. If filenamePrefix is blank, the image files are
     *            copied without name change. Otherwise they are named <filenamePrefix>_i.tif/pdf/epub in the target folder, where i is an
     *            incrementing integer starting at value 1
     * @throws IOException
     */
    private void copyImagesToFolder(Path sourceImageFolder, String copyToDirectory, String filenamePrefix) throws IOException {

        List<Path> dataInSourceImageFolder = StorageProvider.getInstance().listFiles(sourceImageFolder.toString());
        dataInSourceImageFolder.sort(Comparator.comparing(o -> o.toFile().getName().toUpperCase()));
        Files.createDirectories(Paths.get(copyToDirectory));

        int iNumber = 1;
        for (Path currentData : dataInSourceImageFolder) {
            String filename = currentData.getFileName().toString();
            if (Files.isDirectory(currentData)) {
                Path targetDir = Paths.get(copyToDirectory).resolve(currentData.getFileName());
                Files.createDirectories(targetDir);
                StorageProvider.getInstance().copyDirectory(currentData, targetDir);
            } else if (!filename.startsWith(".") && filename.toLowerCase().matches(".*\\.(tiff?|pdf|epub)")) {
                String newFilename = filename;
                if (StringUtils.isNotBlank(filenamePrefix)) {
                    String number = String.format("%04d", iNumber);
                    newFilename = filenamePrefix + "_" + number + "." + FilenameUtils.getExtension(currentData.toString());
                }
                iNumber++;
                StorageProvider.getInstance().copyFile(currentData, Paths.get(copyToDirectory, newFilename));
            }
        }
    }

    private void copyFile(Path file, Path destination) throws IOException {

        if (moveFiles) {
            StorageProvider.getInstance().move(file, destination);
            //            Files.move(file, destination, StandardCopyOption.REPLACE_EXISTING);
        } else {
            if (Files.isDirectory(file)) {
                StorageProvider.getInstance().copyDirectory(file, destination);
            } else {
                StorageProvider.getInstance().copyFile(file, destination);
            }
            //            Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
        }

    }

    /**
     * Checks that the given folder exists, hasn't been modified within the last 30 minutes and contains at least one (image) file and no symlinks or
     * folders
     * 
     * @param imageSourceFolder
     * @throws IOException if an error occured parsing the given folder
     * @throws ImportException if any of the above conditions are met, meaning that the folder is not ready for import
     */
    private void checkImageSourceFolder(Path imageSourceFolder) throws IOException, ImportException {
        if (!Files.exists(imageSourceFolder)) {
            throw new ImportException("Image folder does not exist");
        }
        if (Files.getLastModifiedTime(imageSourceFolder).toInstant().isAfter(Instant.now().minus(getSourceImageFolderModificationBlockTimeout()))) {
            throw new ImportException("Image folder has beend modified in the last 30 minutes");
        }
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

    private Duration getSourceImageFolderModificationBlockTimeout() {
        int minutes = getConfig().getSourceImageFolderMofidicationBlockTimout();
        return Duration.of(minutes, ChronoUnit.MINUTES);
    }

    private String nameProcess(String processTitle, ImportObject io) {
        // set new process title
        String fileName = importFolder + File.separator + processTitle + ".xml";
        io.setProcessTitle(processTitle);
        io.setMetsFilename(fileName);
        return fileName;
    }

    private int addRowProcess(List<Record> recordList, String idColumn, Map<String, Integer> headerOrder, Iterator<Row> rowIterator, int rowCounter) {

        Map<Integer, String> map = new HashMap<>();
        Row row = rowIterator.next();
        rowCounter++;
        int lastColumn = row.getLastCellNum();
        if (lastColumn == -1) {
            return rowCounter;
        }
        for (int cn = 0; cn < lastColumn; cn++) {
            //                while (cellIterator.hasNext()) {
            //                    Cell cell = cellIterator.next();
            Cell cell = row.getCell(cn, MissingCellPolicy.CREATE_NULL_AS_BLANK);
            String value = "";
            switch (cell.getCellType()) {
                case BOOLEAN:
                    value = cell.getBooleanCellValue() ? "true" : "false";
                    break;
                case FORMULA:
                    //                            value = cell.getCellFormula();
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

    private Map<String, Integer> getHeaderOrder(Record record) {
        Object tempObject = record.getObject();

        List<Map<?, ?>> list = (List<Map<?, ?>>) tempObject;
        Map<String, Integer> headerOrder = (Map<String, Integer>) list.get(0);
        Map<Integer, String> rowMap = (Map<Integer, String>) list.get(1);
        return headerOrder;
    }

    private Map<Integer, String> getRowMap(Record record) {
        Object tempObject = record.getObject();

        List<Map<?, ?>> list = (List<Map<?, ?>>) tempObject;
        Map<String, Integer> headerOrder = (Map<String, Integer>) list.get(0);
        Map<Integer, String> rowMap = (Map<Integer, String>) list.get(1);
        return rowMap;
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

        NLIExcelConfig config = new NLIExcelConfig(myconfig);

        return config;
    }

    public static SubnodeConfiguration getTemplateConfig(String workflowTitle) {
        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(title);

        //        XMLConfiguration xmlConfig = ConfigPlugins.getPluginConfig(title);

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
