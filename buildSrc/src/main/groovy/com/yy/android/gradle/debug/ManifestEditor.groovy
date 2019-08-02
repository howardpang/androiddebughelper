/*
 * Copyright 2018-present howard_pang@outlook.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.yy.android.gradle.debug

import com.google.devrel.gmscore.tools.apk.arsc.*

class ManifestEditor {
    static void modifyAndroidManifestToDebuggable(File manifestFile) {
        FileInputStream inputStream = new FileInputStream(manifestFile);
        BinaryResourceFile binaryResourceFile = BinaryResourceFile.fromInputStream(inputStream);
        inputStream.close();
        XmlStartElementChunk applicationChunk = null;
        StringPoolChunk stringPoolChunk = null;
        XmlResourceMapChunk xmlResourceMapChunk = null;
        for (Chunk chunk : binaryResourceFile.getChunks()) {
            if (chunk instanceof XmlChunk) {
                for (Chunk subChunk : ((XmlChunk) chunk).getChunks().values()) {
                    if (subChunk instanceof XmlStartElementChunk) {
                        XmlStartElementChunk xmlStartElementChunk = (XmlStartElementChunk) subChunk;
                        if (xmlStartElementChunk.getName().equals("application")) {
                            applicationChunk = xmlStartElementChunk;
                        }
                    } else if (subChunk instanceof StringPoolChunk) {
                        stringPoolChunk = (StringPoolChunk) subChunk;
                    } else if (subChunk instanceof XmlResourceMapChunk ) {
                        xmlResourceMapChunk = (XmlResourceMapChunk)subChunk;
                    }
                    if (stringPoolChunk != null && applicationChunk != null && xmlResourceMapChunk != null) {
                        break;
                    }
                }
            }
        }

        XmlAttribute debugAttribute = null;
        if (applicationChunk != null) {
            for (XmlAttribute attribute : applicationChunk.getAttributes()) {
                if (attribute.name().equals("debuggable")) {
                    debugAttribute = attribute;
                }
            }
        }
        boolean needModify = false;
        if (debugAttribute != null) {
            if (debugAttribute.rawValueIndex() != -1) {
                debugAttribute.setRawValueIndex(-1);
                needModify = true;
            }
            if (debugAttribute.typedValue().data() != -1) {
                debugAttribute.typedValue().setDataValue(-1);
                needModify = true;
            }
        } else {
            int namespaceIndex = stringPoolChunk.indexOf("http://schemas.android.com/apk/res/android");
            int nameIndex = stringPoolChunk.indexOf("debuggable");
            int rawValueIndex = -1;
            int debugResid = 0x0101000f;
            if (nameIndex == -1) {
                nameIndex = addAddAttribute(stringPoolChunk, xmlResourceMapChunk, "debuggable", debugResid);
            }
            if (namespaceIndex == -1) {
                namespaceIndex = stringPoolChunk.addString("http://schemas.android.com/apk/res/android");
            }
            BinaryResourceValue typedValue = new BinaryResourceValue(8, BinaryResourceValue.Type.INT_BOOLEAN, -1);
            debugAttribute = new XmlAttribute(namespaceIndex, nameIndex, rawValueIndex, typedValue, applicationChunk);
            int i = 0;
            List<XmlAttribute> xmlAttributes = applicationChunk.getAttributes();
            for (; i < xmlAttributes.size(); i++) {
                int resId = xmlResourceMapChunk.getResources().get(xmlAttributes.get(i).nameIndex());
                if (resId > debugResid) {
                    break;
                }
            }
            applicationChunk.addAttribute(i, debugAttribute);
            needModify = true;
        }

        println(" apk is debuggable " + (!needModify))
        if (needModify) {
            FileOutputStream newFile = new FileOutputStream(manifestFile);
            newFile.write(binaryResourceFile.toByteArray());
            newFile.flush();
            newFile.close();
        }
    }

     static int addAddAttribute(StringPoolChunk stringPoolChunk, XmlResourceMapChunk resourceMapChunk, String attributeName, int attributeId) {
        int idx = stringPoolChunk.addString(attributeName);
        List<Integer> resourceIds = resourceMapChunk.getResources();
        while (resourceIds.size() < idx) {
            resourceIds.add(0);
        }
        resourceIds.add(attributeId);
        return idx;
    }
}
