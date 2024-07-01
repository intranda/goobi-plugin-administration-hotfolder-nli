package de.intranda.goobi.plugins.hotfolder.nli.model.data;

import java.util.HashMap;
import java.util.Map;

import software.amazon.awssdk.utils.StringUtils;

public class RecordDataObject implements IRecordDataObject {

    private final Map<String, String> dataMap;

    public RecordDataObject(Map<String, String> dataMap) {
        this.dataMap = dataMap;
    }

    public RecordDataObject() {
        this(new HashMap<String, String>());
    }

    public String getValue(String name) {
        if (StringUtils.isBlank(name)) {
            return "";
        } else {
            return this.dataMap.getOrDefault(name, "");
        }
    }

}
