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

import org.gradle.util.VersionNumber

class R8Adapter {
    /** R8 have resource leak when gradle version less than 3.2.0 (https://r8-review.googlesource.com/c/r8/+/25020)
     * So when gradle version less than 3.2.0, we use custom R8
     **/

    static void splitDex(File dexFile, File classesList, File outputDir) {
        VersionNumber currentVersion = VersionNumber.parse(Utils.androidGradleVersion())
        VersionNumber gradle320Version = VersionNumber.parse("3.2.0")
        String[] splitArgs = [dexFile.path, "--main-dex-list", classesList.path, "--output", outputDir.path]
        if (currentVersion < gradle320Version) {
            com.debughelper.tools.r8.D8.main(splitArgs)
        } else {
            com.android.tools.r8.D8.main(splitArgs)
        }
    }

    static void generateMainDexList(File dexFile, File androidJar, List<File> rules, File classesList) {
        List<String> gmArgs = []
        gmArgs.add(dexFile.path)
        gmArgs.add("--lib")
        gmArgs.add(androidJar.path)
        gmArgs.add("--main-dex-list-output")
        gmArgs.add(classesList.path)
        rules.each {
            gmArgs.add("--main-dex-rules")
            gmArgs.add(it.path)
        }
        String[] args = new String[gmArgs.size()]
        args = gmArgs.toArray(args)
        VersionNumber currentVersion = VersionNumber.parse(Utils.androidGradleVersion())
        VersionNumber gradle320Version = VersionNumber.parse("3.2.0")
        if (currentVersion < gradle320Version) {
            com.debughelper.tools.r8.GenerateMainDexList.main(args)
        } else {
            com.android.tools.r8.GenerateMainDexList.main(args)
        }
    }
}
