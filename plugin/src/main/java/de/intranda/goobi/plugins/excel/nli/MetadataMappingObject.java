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

    private String docType ;

    private String searchField;
    
//    public String getSearchField() {
//        return searchField;
//    }
//    
//    public String getHeaderName() {
//        return headerName;
//    }

}
