package de.intranda.goobi.plugins.hotfolder.nli.model.config;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;

import de.intranda.goobi.plugins.hotfolder.nli.model.data.MetadataRule;
import lombok.Data;

@Data
public class NLIExcelConfig {

    private String publicationType;
    private String collection;
    private int firstLine;
    private int identifierColumn;
    private int conditionalColumn;
    private int rowHeader;
    private int rowDataStart;
    private int rowDataEnd;
    private List<MetadataMappingObject> metadataList = new ArrayList<>();
    private List<PersonMappingObject> personList = new ArrayList<>();
    private List<GroupMappingObject> groupList = new ArrayList<>();
    private MetadataRule processIdentifier;
    private MetadataRule processTitle;
    private MetadataRule imageNamePrefix;
    private MetadataRule importFolder;
    private Integer sourceImageFolderMofidicationBlockTimeout;

    private boolean useOpac = false;
    private String opacName;
    @Deprecated
    private String opacHeader;
    private String searchField;
    private String allowedFilenames;

    private boolean moveImage;
    private boolean requireImportFile;
    private List<String> mandatoryColumns = new ArrayList<>();

    /**
     * loads the &lt;config&gt; block from xml file
     * 
     * @param xmlConfig
     */
    public NLIExcelConfig(SubnodeConfiguration xmlConfig) {

        publicationType = xmlConfig.getString("/publicationType", "Monograph");
        collection = xmlConfig.getString("/collection", "");
        firstLine = xmlConfig.getInt("/firstLine", 1);
        identifierColumn = xmlConfig.getInt("/identifierColumn", 1);
        conditionalColumn = xmlConfig.getInt("/conditionalColumn", identifierColumn);
        processIdentifier = MetadataRule.from(xmlConfig, "/processIdentifier");
        processTitle = MetadataRule.from(xmlConfig, "/processTitle");
        imageNamePrefix = MetadataRule.from(xmlConfig, "/imageNamePrefix");
        importFolder = MetadataRule.from(xmlConfig, "/importFolder");
        sourceImageFolderMofidicationBlockTimeout = xmlConfig.getInt("/sourceImageFolderModificationBlockTimeout", 30);

        rowHeader = xmlConfig.getInt("/rowHeader", 1);
        rowDataStart = xmlConfig.getInt("/rowDataStart", 2);
        rowDataEnd = xmlConfig.getInt("/rowDataEnd", 20000);

        moveImage = xmlConfig.getBoolean("/moveImages", true);
        requireImportFile = xmlConfig.getBoolean("/requireImportFile", true);

        allowedFilenames = xmlConfig.getString("/allowedFilenames", ".*\\.(tiff?|pdf|epub)");

        List<HierarchicalConfiguration> mml = xmlConfig.configurationsAt("//metadata");
        for (HierarchicalConfiguration md : mml) {
            if (md.getBoolean("@mandatory")) {
                mandatoryColumns.add(md.getString("@headerName"));
            }
            if (md.getString("@ugh") != null) {
                metadataList.add(getMetadata(md));
            }
        }

        useOpac = xmlConfig.getBoolean("/useOpac", false);

        if (useOpac) {
            opacName = xmlConfig.getString("/opacName", "arc");
            opacHeader = xmlConfig.getString("/opacHeader", "");
            searchField = xmlConfig.getString("/searchField", "12");
        }
    }

    private MetadataMappingObject getMetadata(HierarchicalConfiguration md) {
        String rulesetName = md.getString("@ugh");
        String propertyName = md.getString("@property");
        //        Integer identifierColumn = md.getInteger("@identifier", null);
        String headerName = md.getString("@headerName", null);
        String normdataHeaderName = md.getString("@normdataHeaderName", null);
        String docType = md.getString("@docType", "child");
        Boolean boMandatory = md.getBoolean("@mandatory", false);
        MetadataRule rule = MetadataRule.from(md);

        MetadataMappingObject mmo = new MetadataMappingObject();
        //        mmo.setIdentifierColumn(identifierColumn);
        mmo.setPropertyName(propertyName);
        mmo.setRulesetName(rulesetName);
        mmo.setNormdataHeaderName(normdataHeaderName);
        mmo.setDocType(docType);
        mmo.setMandatory(boMandatory);
        mmo.setRule(rule);

        return mmo;
    }

    //    private PersonMappingObject getPersons(HierarchicalConfiguration md) {
    //        String rulesetName = md.getString("@ugh");
    //        Integer firstname = md.getInteger("firstname", null);
    //        Integer lastname = md.getInteger("lastname", null);
    //        Integer identifier = md.getInteger("identifier", null);
    //        String headerName = md.getString("nameFieldHeader", null);
    //        String firstnameHeaderName = md.getString("firstnameFieldHeader", null);
    //        String lastnameHeaderName = md.getString("lastnameFieldHeader", null);
    //        String normdataHeaderName = md.getString("@normdataHeaderName", null);
    //        boolean splitName = md.getBoolean("splitName", false);
    //        String splitChar = md.getString("splitChar", " ");
    //        boolean firstNameIsFirstPart = md.getBoolean("splitName/@firstNameIsFirstPart", false);
    //        String docType = md.getString("@docType", "child");
    //
    //        PersonMappingObject pmo = new PersonMappingObject();
    //        pmo.setFirstnameColumn(firstname);
    //        pmo.setLastnameColumn(lastname);
    //        pmo.setIdentifierColumn(identifier);
    //        pmo.setRulesetName(rulesetName);
    //        pmo.setHeaderName(headerName);
    //        pmo.setNormdataHeaderName(normdataHeaderName);
    //
    //        pmo.setFirstnameHeaderName(firstnameHeaderName);
    //        pmo.setLastnameHeaderName(lastnameHeaderName);
    //        pmo.setSplitChar(splitChar);
    //        pmo.setSplitName(splitName);
    //        pmo.setFirstNameIsFirst(firstNameIsFirstPart);
    //        pmo.setDocType(docType);
    //        return pmo;
    //
    //    }

}
