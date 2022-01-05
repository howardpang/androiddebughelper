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
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.incremental.IncrementalTaskInputs

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class ApkUpdateTask extends DefaultTask {
    private static final String[] APP_ABIS = ["armeabi", "armeabi-v7a", "x86", "mips", "arm64-v8a", "x86_64", "mips64"]
    private static final String certificatesSuffixReg = "^.*?\\.(SF|RSA|DSA)\$"
    @Internal
    String appId
    @Internal
    Project project
    @Internal
    File apkToUpdate
    private SigningConfig signingConfig
    private int minSdkVersion
    @Internal
    HostExtension hostExtension
    @Internal
    File hostNativeLibDir

    @InputFiles
    List<File> inputDirs

    @OutputDirectory
    File outputDir

    @TaskAction
    void execute(IncrementalTaskInputs inputs) {
        List<String> hostOriginalCertificates = []
        Map<File, String> filesToUpdate = [:]
        if (!inputs.incremental) {
            apkToUpdate.delete()
            hostNativeLibDir.deleteDir()
            hostNativeLibDir.mkdirs()
            ZipFile zipFile = new ZipFile(hostExtension.hostApk)
            Set<String> abis = []
            def entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement()
                if (ze.name.startsWith("lib") && abis.size() != APP_ABIS.size()) {
                    int pos = ze.name.indexOf("/", 4)
                    if (pos != -1) {
                        String abi = ze.name.substring(4, pos)
                        String result = APP_ABIS.find { abi == it }
                        if (result != null) {
                            File abiDir = new File(hostNativeLibDir, result)
                            if (abiDir.mkdirs()) {
                                abis.add(result)
                            }
                        }
                    }
                } else if (ze.name.startsWith("META-INF")) {
                    String subStr = ze.name.substring(9)
                    if (subStr != null && subStr.matches(certificatesSuffixReg)) {
                        hostOriginalCertificates.add(ze.name)
                    }
                }
            }
            zipFile.close()

            inputDirs.each {
                project.fileTree(it).include("**/*.so").each {File so ->
                    boolean shouldUpdateSo = abis.contains(so.parentFile.name)
                    if (hostExtension.excludeSo != null && shouldUpdateSo) {
                        shouldUpdateSo = (hostExtension.excludeSo.find { it == so.name } == null)
                    }
                    if (shouldUpdateSo) {
                        String path = "lib/${so.parentFile.name}/${so.name}"
                        filesToUpdate.put(so, path)
                    }
                }
                project.fileTree(it).include("*.dex", "AndroidManifest.xml").each { dex ->
                    filesToUpdate.put(dex, "${dex.name}")
                }
            }
        }else {
            Set<String> abis = []
            hostNativeLibDir.eachFile {
                abis.add(it.name)
            }
            inputs.outOfDate { change ->
                if (change.file.name.endsWith(".dex")) {
                    filesToUpdate.put(change.file, "${change.file.name}")
                }else if (change.file.name.endsWith(".so")) {
                    boolean shouldUpdateSo = abis.contains(change.file.parentFile.name)
                    if (hostExtension.excludeSo != null && shouldUpdateSo) {
                       shouldUpdateSo = (hostExtension.excludeSo.find { it == change.file.name} == null)
                    }
                    if (shouldUpdateSo) {
                        String path = "lib/${change.file.parentFile.name}/${change.file.name}"
                        filesToUpdate.put(change.file, path)
                    }
                }
            }
        }

        if (!apkToUpdate.exists()) {
            File hostApkFile = new File(hostExtension.hostApk)
            project.copy {
                from hostApkFile
                into apkToUpdate.parentFile
                rename hostApkFile.name, apkToUpdate.name
            }
        }

        ApkUpdater apkUpdater
        apkUpdater = new ApkUpdater(apkToUpdate, signingConfig, minSdkVersion, true)
        apkUpdater.updateFiles(filesToUpdate)
        //Delete original certificates
        hostOriginalCertificates.each {
            apkUpdater.deleteFile(it)
        }
        hostExtension.filesShouldDelete.each {
            apkUpdater.deleteFile(it)
        }
        apkUpdater.close()
        //Note!!! we must output a file to outputDir, otherwise, the incremental task can't execute correctly
        File tmp = new File(outputDir, "tmp.txt")
        tmp.createNewFile()
        if (GradleApiAdapter.isGradleVersionGreaterOrEqualTo("7.0.0")) {
            createOutputMetadataJson(appId, apkToUpdate.name)
        }
    }

    void createOutputMetadataJson(String appId, String apkName) {
        //If there no 'output-metadata.json' file, AS won't install apk to device
        File jsonFile = new File(apkToUpdate.parentFile, "output-metadata.json")
        if (jsonFile.exists()) {
            return
        }
        def pw = new PrintWriter(jsonFile.newWriter(false))
        pw.print("""{
  "version": 3,
  "artifactType": {
    "type": "APK",
    "kind": "Directory"
  },
  "applicationId": "${appId}",
  "variantName": "debug",
  "elements": [
    {
      "type": "SINGLE",
      "filters": [],
      "attributes": [],
      "versionCode": 1,
      "versionName": "1.0",
      "outputFile": "${apkName}"
    }
  ],
  "elementType": "File"
}
""")
        pw.flush()
        pw.close()
    }

    void configure(Project prj, File apk, SigningConfig signingConfig, int minSdkVersion, HostExtension hostExtension, String appId ) {
        this.project = prj
        this.apkToUpdate = apk
        this.appId = appId
        this.signingConfig = signingConfig
        this.minSdkVersion = minSdkVersion
        this.hostExtension = hostExtension
        this.hostNativeLibDir = new File(project.buildDir, "debughelp/hostNativeLibDir")
        String keyStore = signingConfig.storeFile.path
        String storePass = signingConfig.storePassword
        String alias = signingConfig.keyAlias
        println "Sign apk, storeFile: " + keyStore + " storePass:" + storePass + " alias:" + alias + " >> " + apk
    }
}
