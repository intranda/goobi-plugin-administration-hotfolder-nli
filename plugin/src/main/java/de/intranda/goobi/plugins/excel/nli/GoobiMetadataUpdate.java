package de.intranda.goobi.plugins.excel.nli;

import org.goobi.beans.Process;

import de.sub.goobi.persistence.managers.ProcessManager;

public class GoobiMetadataUpdate {

    public static boolean checkForExistingProcess(String processname) {
        long anzahl = 0;

        anzahl = ProcessManager.countProcessTitle(processname, null);

        if (anzahl != 0) {
            return true;
        } else {
            return false;
        }
    }

    public static Process loadProcess(String processname) {
        Process process = ProcessManager.getProcessByTitle(processname);
        return process;
    }

}
