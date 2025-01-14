package de.intranda.goobi.plugins.hotfolder.nli.model.data;

import java.util.Map;

public class ExcelDataObject implements IRecordDataObject {

    private final Map<String, Integer> headerOrder;
    private final Map<Integer, String> rowMap;
    private final Map<String, String> additionalValues;

    public ExcelDataObject(Map<String, Integer> headerOrder, Map<Integer, String> rowMap, Map<String, String> additionalValues) {
        this.headerOrder = headerOrder;
        this.rowMap = rowMap;
        this.additionalValues = additionalValues;
    }

    @Override
    public String getValue(String name) {
        return rowMap.getOrDefault(headerOrder.getOrDefault(name, -1), additionalValues.getOrDefault(name, ""));
    }

}
