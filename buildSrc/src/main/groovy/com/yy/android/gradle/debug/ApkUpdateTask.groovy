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

import com.android.builder.model.SigningConfig
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.incremental.IncrementalTaskInputs

class ApkUpdateTask extends DefaultTask {
    private Project project
    private File apkToUpdate
    private SigningConfig signingConfig
    private int minSdkVersion

    @InputFiles
    List<File> inputDirs

    @OutputDirectory
    File outputDir

    @TaskAction
    void execute(IncrementalTaskInputs inputs) {
        Map<File, String> filesToUpdate = [:]
        if (!inputs.incremental) {
            inputDirs.each {
                project.fileTree(it).include("**/*.so").each { so->
                    String path = "lib/${so.parentFile.name}/${so.name}"
                    filesToUpdate.put(so, path)
                }
                project.fileTree(it).include("*.dex").each { dex->
                    filesToUpdate.put(dex, "${dex.name}")
                }
            }
        }else {
            inputs.outOfDate { change ->
                if (change.file.name.endsWith(".dex")) {
                    filesToUpdate.put(change.file, "${change.file.name}")
                }else if (change.file.name.endsWith(".so")) {
                    String path = "lib/${change.file.parentFile.name}/${change.file.name}"
                    filesToUpdate.put(change.file, path)
                }
            }
        }

        ApkUpdater apkUpdater
        apkUpdater = new ApkUpdater(apkToUpdate, signingConfig, minSdkVersion, true)
        apkUpdater.updateFiles(filesToUpdate)
        apkUpdater.close()
    }

    void configure(Project prj, File apk, SigningConfig signingConfig, int minSdkVersion ) {
        this.project = prj
        this.apkToUpdate = apk
        this.signingConfig = signingConfig
        this.minSdkVersion = minSdkVersion
        String keyStore = signingConfig.storeFile.path
        String storePass = signingConfig.storePassword
        String alias = signingConfig.keyAlias
        println "Sign apk, storeFile: " + keyStore + " storePass:" + storePass + " alias:" + alias + " >> " + apk
    }
}
