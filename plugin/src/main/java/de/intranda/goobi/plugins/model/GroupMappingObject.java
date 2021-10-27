package de.intranda.goobi.plugins.model;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class GroupMappingObject {

    private String rulesetName;
    private List<MetadataMappingObject> metadataList = new ArrayList<>();
    private List<PersonMappingObject> personList = new ArrayList<>();

    private String docType;
}

