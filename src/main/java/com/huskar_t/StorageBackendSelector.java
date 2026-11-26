package com.huskar_t;

import org.dom4j.Element;
import org.dom4j.io.SAXReader;

import java.io.File;

final class StorageBackendSelector {

    private StorageBackendSelector() {
    }

    static boolean readIotdbEnabled(File configFile) throws Exception {
        if (!configFile.isFile()) {
            return false;
        }
        SAXReader reader = new SAXReader();
        reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Element root = reader.read(configFile).getRootElement();
        String selection = root.elementTextTrim("iotdbEnabled");
        if (selection == null || selection.isEmpty() || "false".equalsIgnoreCase(selection)) {
            return false;
        }
        if ("true".equalsIgnoreCase(selection)) {
            return true;
        }
        throw new IllegalArgumentException("iotdbEnabled must be true or false");
    }
}
