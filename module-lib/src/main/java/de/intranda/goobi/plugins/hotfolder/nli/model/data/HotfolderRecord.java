package de.intranda.goobi.plugins.hotfolder.nli.model.data;

import org.goobi.production.importer.Record;

public class HotfolderRecord extends Record {

    @Override
    public Object getObject() {
        return getDataObject();
    }

    @Override
    public void setObject(Object object) {
        if (object instanceof IRecordDataObject) {
            this.setDataObject((IRecordDataObject) object);
        } else {
            throw new IllegalArgumentException("Object must be an instance of IRecordDataObject");
        }
    }

    public void setDataObject(IRecordDataObject object) {
        super.setObject(object);
    }

    public IRecordDataObject getDataObject() {
        return (IRecordDataObject) super.getObject();
    }

}
