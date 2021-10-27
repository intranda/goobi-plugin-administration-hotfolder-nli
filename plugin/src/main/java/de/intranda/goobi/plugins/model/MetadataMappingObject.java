package de.intranda.goobi.plugins.model;

import lombok.Data;

@Data
public class MetadataMappingObject {

    private String rulesetName;
    private String propertyName;
    private Integer excelColumn;

    private String headerName;

    private String normdataHeaderName;

    private String docType;

    private String searchField;
    private Boolean mandatory;
    
    public boolean isMandatory() {
        return mandatory;
    }

}
