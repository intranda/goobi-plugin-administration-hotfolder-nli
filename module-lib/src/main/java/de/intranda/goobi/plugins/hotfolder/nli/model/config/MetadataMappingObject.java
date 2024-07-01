package de.intranda.goobi.plugins.hotfolder.nli.model.config;

import de.intranda.goobi.plugins.hotfolder.nli.model.data.MetadataRule;
import lombok.Data;

@Data
public class MetadataMappingObject {

    private String rulesetName;
    private String propertyName;

    private String normdataHeaderName;

    private String docType;

    private Boolean mandatory;

    private MetadataRule rule;

    public boolean isMandatory() {
        return mandatory;
    }

}
