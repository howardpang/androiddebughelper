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

class DxDexParser implements DexParser {
    @Override
    Set<String> getTypeList(File dexFile) {
       Set<String> typeList = new HashSet<>()
        if (dexFile != null && dexFile.exists()) {
            com.android.dex.Dex dex = new com.android.dex.Dex(dexFile)
            dex.classDefs().each {
                String typeName = dex.typeNames().get(it.typeIndex)
                typeName = typeName.substring(1, typeName.length() - 1)
                typeList.add(typeName)
            }
        }
        return typeList
    }
}
