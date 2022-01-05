package de.intranda.goobi.plugins.model;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.ImportPluginException;
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

    private Fileformat getRecordFromCatalogue(Map<Integer, String> rowMap, Map<String, Integer> headerOrder, String catalogue)
            throws ImportPluginException {
        IOpacPlugin myImportOpac = null;
        ConfigOpacCatalogue coc = null;
        for (ConfigOpacCatalogue configOpacCatalogue : ConfigOpac.getInstance().getAllCatalogues(workflowTitle)) {
            if (configOpacCatalogue.getTitle().equals(catalogue)) {
                myImportOpac = configOpacCatalogue.getOpacPlugin();
                coc = configOpacCatalogue;
            }
        }
        if (myImportOpac == null) {
            throw new ImportPluginException("Opac plugin for catalogue " + catalogue + " not found. Abort.");
        }
        Fileformat myRdf = null;
        DocStruct ds = null;

        String identifier = rowMap.get(headerOrder.get(config.getIdentifierHeaderName()));
        try {

            myRdf = myImportOpac.search(config.getSearchField(), identifier, coc, prefs);
            if (myRdf == null) {
                throw new ImportPluginException("Could not import record " + identifier
                        + ". Usually this means a ruleset mapping is not correct or the record can not be found in the catalogue.");
            }
        } catch (Exception e1) {
            throw new ImportPluginException("Could not import record " + identifier
                    + ". Usually this means a ruleset mapping is not correct or the record can not be found in the catalogue.");
        }

        try {
            ds = myRdf.getDigitalDocument().getLogicalDocStruct();
            if (ds.getType().isAnchor()) {
                if (ds.getAllChildren() == null || ds.getAllChildren().isEmpty()) {
                    throw new ImportPluginException(
                            "Could not import record " + identifier + ". Found anchor file, but no children. Try to import the child record.");
                }
                ds = ds.getAllChildren().get(0);
            }
        } catch (PreferencesException e1) {
            throw new ImportPluginException("Could not import record " + identifier
                    + ". Usually this means a ruleset mapping is not correct or the record can not be found in the catalogue.");
        }

        try {
            ats = myImportOpac.getAtstsl();

            List<? extends Metadata> sort = ds.getAllMetadataByType(prefs.getMetadataTypeByName("CurrentNoSorting"));
            if (sort != null && !sort.isEmpty()) {
                volumeNumber = sort.get(0).getValue();
            }

        } catch (Exception e) {
            ats = "";
        }

        return myRdf;
    }

    @SuppressWarnings("unchecked")
    public ImportObject generateFile(Record record, HotfolderFolder hff) {

        //reset the template if necessary:
        if (hff.getTemplateName() != workflowTitle) {
            workflowTitle = hff.getTemplateName();
            config = null;
            getConfig();
        }

        String timestamp = Long.toString(System.currentTimeMillis());
        ImportObject io = new ImportObject();
        try {

            Object tempObject = record.getObject();

            List<Map<?, ?>> list = (List<Map<?, ?>>) tempObject;
            Map<String, Integer> headerOrder = (Map<String, Integer>) list.get(0);
            Map<Integer, String> rowMap = (Map<Integer, String>) list.get(1);

            //check mandatory fields:
            try {
                for (MetadataMappingObject mmo : config.getMetadataList()) {
                    if (mmo.isMandatory()) {
                        Integer col = headerOrder.get(mmo.getHeaderName());
                        if (col == null || rowMap.get(col) == null || rowMap.get(col).isEmpty()) {
                            io.setErrorMessage("Missing column: " + mmo.getHeaderName());
                            io.setImportReturnValue(ImportReturnValue.NoData);
                            return null;
                        }
                    }
                }
            } catch (Exception e) {
                log.error(e);
                io.setErrorMessage(e.getMessage());
                io.setImportReturnValue(ImportReturnValue.NoData);
                return null;
            }

            // generate a mets file
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
                try {
                    boolean validRequest = false;
                    for (MetadataMappingObject mmo : config.getMetadataList()) {
                        if (StringUtils.isNotBlank(mmo.getSearchField()) && headerOrder.get(mmo.getHeaderName()) != null) {
                            validRequest = true;
                            break;
                        }
                    }

                    if (!validRequest) {
                        if (StringUtils.isBlank(config.getIdentifierHeaderName())) {
                            Helper.setFehlerMeldung("Cannot request catalogue, no identifier column defined");
                            log.error("Cannot request catalogue, no identifier column defined");
                            return null;
                        }

                        //                        Integer columnNumber = headerOrder.get(config.getIdentifierHeaderName());

                        //                            if (columnNumber == null) {
                        //                                Helper.setFehlerMeldung("Cannot request catalogue, identifier column '" + config.getIdentifierHeaderName()
                        //                                        + "' not found in excel file.");
                        //                                log.error("Cannot request catalogue, identifier column '" + config.getIdentifierHeaderName()
                        //                                        + "' not found in excel file.");
                        //                                return Collections.emptyList();
                        //                            }

                        String catalogueIdentifier = rowMap.get(headerOrder.get(config.getIdentifierHeaderName()));
                        if (StringUtils.isBlank(catalogueIdentifier)) {
                            return null;
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

                } catch (Exception e) {
                    log.error(e);
                    io.setErrorMessage(e.getMessage());
                    io.setImportReturnValue(ImportReturnValue.NoData);
                    return null;
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

            // create file name for mets file
            String fileName = null;

            // create importobject for massimport
            io.setImportReturnValue(ImportReturnValue.ExportFinished);

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
                        if (existingMetadata.isEmpty()) {
                            existingMetadata = (List<Metadata>) anchor.getAllMetadataByType(prefs.getMetadataTypeByName(mmo.getRulesetName()));
                        }
                        if (!existingMetadata.isEmpty()) {
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
                    //                    // create a default title
                    //                    if (mmo.getRulesetName().equalsIgnoreCase("CatalogIDDigital") && !"anchor".equals(mmo.getDocType())) {
                    //                        fileName = importFolder + File.separator + value + ".xml";
                    //                        io.setProcessTitle(value);
                    //                        io.setMetsFilename(fileName);
                    //                    }
                }

                if (StringUtils.isNotBlank(mmo.getPropertyName()) && StringUtils.isNotBlank(value)) {
                    Processproperty p = new Processproperty();
                    p.setTitel(mmo.getPropertyName());
                    p.setWert(value);
                    io.getProcessProperties().add(p);
                }
            }

            //Name the process:
            currentIdentifier = rowMap.get(headerOrder.get(config.getProcessHeaderName()));
            String processName = hff.getProjectFolder().getFileName() + "_" + currentIdentifier;
            currentIdentifier = processName;
            fileName = nameProcess(processName, io);

            // write mets file into import folder
            ff.write(fileName);

            moveImages(io, headerOrder, rowMap, fileName, hff);

            // check if the process exists
            if (replaceExisting) {
                boolean dataReplaced = false;
                Process existingProcess = ProcessManager.getProcessByExactTitle(io.getProcessTitle());
                if (existingProcess != null) {
                    try {
                        existingProcess.writeMetadataFile(ff);
                        dataReplaced = true;
                    } catch (WriteException | PreferencesException | IOException | InterruptedException | SwapException | DAOException e) {
                        log.error(e);
                        return null;
                    }

                    Path sourceRootFolder = Paths.get(record.getData());
                    moveImageIntoProcessFolder(existingProcess, sourceRootFolder);
                }
                if (dataReplaced) {
                    //remove temp file
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
                    return null;
                }
            }

        } catch (WriteException | PreferencesException | MetadataTypeNotAllowedException | TypeNotAllowedForParentException | IOException e) {
            io.setImportReturnValue(ImportReturnValue.WriteError);
            io.setErrorMessage(e.getMessage());
        }

        return io;
    }

    public List<ImportObject> generateFiles(List<Record> records, HotfolderFolder hff) {
        List<ImportObject> answer = new ArrayList<>();

        for (Record record : records) {
            ImportObject io = generateFile(record, hff);
            if (io != null) {
                answer.add(io);
            }
        }

        return answer;

    }

    private void moveImages(ImportObject io, Map<String, Integer> headerOrder, Map<Integer, String> rowMap, String fileName, HotfolderFolder hff) {

        String imageFolder = rowMap.get(headerOrder.get(config.getProcessHeaderName()));
        Path imageSourceFolder = Paths.get(hff.getProjectFolder().toString(), imageFolder);

        currentIdentifier = rowMap.get(headerOrder.get(config.getImagesHeaderName()));
        if (currentIdentifier == null || currentIdentifier.isEmpty()) {
            currentIdentifier = io.getProcessTitle();
        }

        if (Files.exists(imageSourceFolder) && Files.isDirectory(imageSourceFolder)) {

            String foldername = fileName.replace(".xml", "");
            String folderNameRule = ConfigurationHelper.getInstance().getProcessImagesMasterDirectoryName();
            folderNameRule = folderNameRule.replace("{processtitle}", io.getProcessTitle());

            Path path = Paths.get(foldername, "images", folderNameRule);
            try {

                copyImagesToFolder(imageSourceFolder, path.toString());
                if (config.isMoveImage()) {
                    StorageProvider.getInstance().deleteDir(imageSourceFolder);
                }
            } catch (IOException e) {
                System.console().printf(e.getMessage());
                log.error(e);
            }

        }
    }

    private String nameProcess(String processTitle, ImportObject io) {

        // set new process title
        String fileName = importFolder + File.separator + processTitle + ".xml";
        io.setProcessTitle(processTitle);
        io.setMetsFilename(fileName);
        return fileName;
    }

    private void moveImageIntoProcessFolder(Process existingProcess, Path sourceRootFolder) {
        if (StorageProvider.getInstance().isFileExists(sourceRootFolder)) {
            Path sourceImageFolder = Paths.get(sourceRootFolder.toString(), "images");
            Path sourceOcrFolder = Paths.get(sourceRootFolder.toString(), "ocr");

            if (StorageProvider.getInstance().isDirectory(sourceImageFolder)) {
                try {
                    String copyToDirectory = existingProcess.getImagesDirectory();

                    copyImagesToFolder(sourceImageFolder, copyToDirectory);
                    if (config.isMoveImage()) {
                        StorageProvider.getInstance().deleteDir(sourceImageFolder);
                    }
                } catch (IOException | InterruptedException | SwapException | DAOException e) {
                    System.console().printf(e.getMessage());
                    log.error(e);
                }
            }

            // ocr
            if (Files.exists(sourceOcrFolder)) {
                List<Path> dataInSourceImageFolder = StorageProvider.getInstance().listFiles(sourceOcrFolder.toString());
                for (Path currentData : dataInSourceImageFolder) {
                    if (Files.isRegularFile(currentData)) {
                        try {
                            copyFile(currentData, Paths.get(existingProcess.getOcrDirectory(), currentData.getFileName().toString()));
                        } catch (IOException | SwapException | DAOException | InterruptedException e) {
                            log.error(e);
                        }
                    } else {
                        try {
                            FileUtils.copyDirectory(currentData.toFile(), Paths.get(existingProcess.getOcrDirectory()).toFile());
                        } catch (IOException | SwapException | DAOException | InterruptedException e) {
                            log.error(e);
                        }
                    }
                }
            }
        }
    }

    private void copyImagesToFolder(Path sourceImageFolder, String copyToDirectory) throws IOException {

        List<Path> dataInSourceImageFolder = StorageProvider.getInstance().listFiles(sourceImageFolder.toString());
        dataInSourceImageFolder.sort(Comparator.comparing(o -> o.toFile().getName().toUpperCase()));
        Files.createDirectories(Paths.get(copyToDirectory));

        int iNumber = 1;
        for (Path currentData : dataInSourceImageFolder) {
            if (Files.isDirectory(currentData)) {
                StorageProvider.getInstance().copyDirectory(currentData, Paths.get(copyToDirectory));
            } else {
                String number = String.format("%04d", iNumber);
                String newFilename = currentIdentifier + "_" + number + "." + FilenameUtils.getExtension(currentData.toString());
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

    //    @Override
    //    public List<Record> splitRecords(String records) {
    //        return null;
    //    }

    //    @Override
    public List<Record> generateRecordsFromFile(File file, List<Path> processFolders) throws IOException {

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
            prefs = template.getRegelsatz().getPreferences();
        }

        return config;
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

        if (myconfig != null) {
            replaceExisting = myconfig.getBoolean("replaceExistingProcesses", false);
            moveFiles = myconfig.getBoolean("moveFiles", false);
        }

        NLIExcelConfig config = new NLIExcelConfig(myconfig);

        return config;
    }

    //    this.co = ConfigOpac.getInstance().getAllCatalogues();
    //
    //
    //    public void setOpacKatalog(String opacKatalog) {
    //        if (!this.opacKatalog.equals(opacKatalog)) {
    //            this.opacKatalog = opacKatalog;
    //            currentCatalogue = null;
    //            for (ConfigOpacCatalogue catalogue : catalogues) {
    //                if (opacKatalog.equals(catalogue.getTitle())) {
    //                    currentCatalogue = catalogue;
    //                    break;
    //                }
    //            }
    //
    //            if (currentCatalogue == null) {
    //                // get first catalogue in case configured catalogue doesn't exist
    //                currentCatalogue = catalogues.get(0);
    //            }
    //            if (currentCatalogue != null) {
    //                currentCatalogue.getOpacPlugin().setTemplateName(prozessVorlage.getTitel());
    //                currentCatalogue.getOpacPlugin().setProjectName(prozessVorlage.getProjekt().getTitel());
    //            }
    //        }
    //    }
}
