package de.intranda.goobi.plugins.excel.nli;

import lombok.Data;
 

@Data
public class MetadataMappingObject {

    private String rulesetName;
    private String propertyName;
    private Integer excelColumn;
    //    private Integer identifierColumn;

    private String headerName;

    private String normdataHeaderName;

    private String docType;

    private String searchField;
    private Boolean mandatory;

    public String getDocType() {
        return docType;
    }

    public String getSearchField() {
        return searchField;
    }

    public String getHeaderName() {
        return headerName;
    }

    public String getNormdataHeaderName() {
        return normdataHeaderName;
    }

    public String getRulesetName() {
        return rulesetName;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public Integer getExcelColumn() {
        return excelColumn;
    }
    
    public Boolean isMandatory() {
        return mandatory;
    }

    public void setExcelColumn(Integer columnNumber) {
        this.excelColumn = columnNumber;
    }

    public void setPropertyName(String propertyName2) {
        this.propertyName = propertyName2;
    }

    public void setRulesetName(String rulesetName2) {
        this.rulesetName = rulesetName2;
    }

    public void setNormdataHeaderName(String normdataHeaderName2) {
        this.normdataHeaderName = normdataHeaderName2;
    }

    public void setDocType(String docType2) {
        this.docType = docType2;
    }

    public void setHeaderName(String headerName2) {
        this.headerName = headerName2;
    }

    public void setSearchField(String searchField) {
        this.searchField = searchField;
    }

    public void setMandatory(Boolean boMandatory) {
       this.mandatory = boMandatory;
    }
}
