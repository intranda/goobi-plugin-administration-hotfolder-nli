package de.intranda.goobi.plugins.excel.nli;

import java.util.ArrayList;
import java.util.List;

//import lombok.Data;

public class Line {

    private int id = 0;
    private String orderAsNumber = "";
    private String classification = "";
    private String title = "";
    private int startImage = 0;
    private String identifier = "";
    private int numberOfImages = 0;
    private List<String> keywords = new ArrayList<String>();

    public void addToOrder(int number) {
        if (!orderAsNumber.isEmpty()) {
            orderAsNumber += "." + number;
        } else {
            orderAsNumber = "" + number;
        }
    }

    public void addToClassification(String value) {
        if (classification.isEmpty()) {
            classification = "bibliotheken#100universitaetsbibliothek#200Realkatalog#" + value;
            //            classification = value;
        } else {
            classification += "#" + value;
        }
        keywords.add(value);
    }

}
