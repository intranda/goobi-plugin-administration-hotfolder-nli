package de.intranda.goobi.plugins.hotfolder.nli.model.data;

import java.util.StringTokenizer;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.lang.StringUtils;

import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.VariableReplacer;
import lombok.Data;

@Data
public class MetadataRule {

    private final String rule;
    private final String replacementRegex;
    private final String replacementSubstitution;

    public MetadataRule(String rule, String substitution) {
        this(rule, ConfigurationHelper.getInstance().getProcessTitleReplacementRegex(), substitution);
    }

    public MetadataRule(String rule, String replacementRegex, String substitution) {
        this.rule = rule;
        this.replacementSubstitution = substitution;
        this.replacementRegex = replacementRegex;
    }

    public String getValue(IRecordDataObject data) {
        return this.getValue(data, null);
    }

    public String getValue(IRecordDataObject data, VariableReplacer vr) {
        String timestamp = Long.toString(System.currentTimeMillis());
        StringBuilder titleValue = new StringBuilder();
        StringTokenizer tokenizer = new StringTokenizer(this.rule, "+");
        while (tokenizer.hasMoreTokens()) {
            String myString = tokenizer.nextToken();
            /*
             * wenn der String mit ' anfängt und mit ' endet, dann den Inhalt so übernehmen
             */
            if (myString.startsWith("'") && myString.endsWith("'")) {
                titleValue.append(myString.substring(1, myString.length() - 1));
            } else if ("Signatur".equalsIgnoreCase(myString) || "Shelfmark".equalsIgnoreCase(myString)) {
                if (StringUtils.isNotBlank(data.getValue(myString))) {
                    // replace white spaces with dash, remove other special characters
                    titleValue.append(data.getValue(myString).replace(" ", "-").replace("/", "-").replaceAll("[^\\w-]", ""));
                }
            } else if ("timestamp".equalsIgnoreCase(myString)) {
                titleValue.append(timestamp);
            } else if (myString.startsWith("(") || myString.startsWith("{")) {
                if (vr != null) {
                    titleValue.append(vr.replace(myString));
                } else {
                    titleValue.append(myString);
                }
            } else {
                String s = data.getValue(myString);
                titleValue.append(s != null ? s : "");
            }
        }
        String newTitle = titleValue.toString();
        if (newTitle.endsWith("_")) {
            newTitle = newTitle.substring(0, newTitle.length() - 1);
        }
        // remove non-ascii characters for the sake of TIFF header limits
        String filteredTitle = newTitle.replaceAll(replacementRegex, replacementSubstitution);
        return filteredTitle;
    }

    public static MetadataRule from(HierarchicalConfiguration parentConfig, String path) {
        return parentConfig.configurationsAt(path).stream().findAny().map(config -> {
            return from(config);
        }).orElse(new MetadataRule("", "", ""));
    }

    public static MetadataRule from(HierarchicalConfiguration config) {
        if (config.containsKey(".")) {
            return new MetadataRule(config.getString("."), "", "");
        } else {
            String rule = config.getString("/rule");
            String replace = config.getString("rule/@replace", "");
            String replacement = config.getString("rule/@replaceWith", "");
            return new MetadataRule(rule, replace, replacement);
        }
    }

    public boolean isBlank() {
        return StringUtils.isBlank(this.rule);
    }

}
