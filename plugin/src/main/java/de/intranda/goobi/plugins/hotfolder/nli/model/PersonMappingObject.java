package de.intranda.goobi.plugins.hotfolder.nli.model;

import lombok.Data;

@Data
public class PersonMappingObject {

    private String rulesetName;
    private Integer firstnameColumn;
    private Integer lastnameColumn;
    private Integer identifierColumn;

    private String headerName;
    private String normdataHeaderName;

    private String firstnameHeaderName;
    private String lastnameHeaderName;
    private boolean splitName;
    private String splitChar;
    private boolean firstNameIsFirst;

    private String docType;
}

