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

import com.android.build.gradle.internal.api.ApplicationVariantImpl
import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.incremental.IncrementalTaskInputs
import org.gradle.api.file.FileTree
import org.gradle.work.FileChange
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
//import org.gradle.work.ChangeType
import org.gradle.api.file.FileType

import java.nio.file.Files

class CollectSubPrjClassTask extends DefaultTask {
    @Internal
    String variantName
    @Internal
    HostExtension hostExtension
    @Internal
    File classOutputDir
    @Internal
    CustomTransform customTransform

    @Incremental
    @InputFiles
    FileCollection classesDirs

    @OutputDirectory
    File outputDir

    @TaskAction
    void execute(IncrementalTaskInputs inputs) {
        if (!hostExtension.updateJavaClass) {
            return
        }
        def classesToUpdateInfo = [:] // map [dir, Set<classes>]
        if (!inputs.incremental) {
            classesDirs.each { dir ->
                classesToUpdateInfo[dir] = project.fileTree(dir).exclude("**/R.class", "**/R\$*.class").files
            }
        } else {
            inputs.outOfDate { change ->
                if (change.file.isFile() && !change.file.name.matches("R\\.class") && !change.file.name.matches("R\\\$.*\\.class")) {
                    File dir = classesDirs.find { change.file.path.startsWith(it.path) }
                    Set<File> files = classesToUpdateInfo[dir]
                    if (files == null) {
                        files = []
                        classesToUpdateInfo[dir] = files
                    }
                    files.add(change.file)
                }
            }
        }
        if (!classesToUpdateInfo.isEmpty()) {
            classOutputDir.deleteDir()
            classOutputDir.mkdirs()
        }

        classesToUpdateInfo.each {File k,Set v ->
            v.each {File clazz->
                String className = clazz.path.substring(k.path.length())
                File linkFile = new File(classOutputDir, className)
                if (!linkFile.parentFile.exists()) {
                    linkFile.parentFile.mkdirs()
                }
                if (!linkFile.exists() && clazz.exists()) {
                    Files.createSymbolicLink(linkFile.toPath(), clazz.toPath())
                }
            }
        }
        if (!classesToUpdateInfo.isEmpty()) {
            customTransform.variantClassDir.put(variantName, classOutputDir)
        }

        //Note!!! we must output a file to outputDir, otherwise, the incremental task can't execute correctly
        File tmp = new File(outputDir, "tmp.txt")
        tmp.createNewFile()
    }

//    @TaskAction
//    void execute(InputChanges inputChanges) {
//        if (!inputChanges.incremental) {
//            //Should clean dex because there may contain new dex that not belong to host apk
//            dexInfoDir.deleteDir()
//            dexInfoDir.mkdirs()
//            outputDir.deleteDir()
//            outputDir.mkdirs()
//            extractFilesFromHostApk()
//        }
//        if (!hostExtension.updateJavaClass) {
//            return
//        }
//
//        ProcessOutput output
//        Closeable ignored = output = outputHandler.createOutput()
//        DxContext dxContext = new DxContext(output.getStandardOutput(), output.getErrorOutput())
//        def classesToUpdateInfo = [:] // map [dir, Set<classes>]
//        if (!inputChanges.incremental) {
//            classesDirs.each { dir ->
//                classesToUpdateInfo[dir] = project.fileTree(dir).exclude("**/R.class", "**/R\$*.class").files
//            }
//            generateSecondlyDexToUpdate(classesToUpdateInfo, dxContext)
//        } else {
//            inputChanges.getFileChanges(classesDirs).each { FileChange change ->
//                if (change.fileType == FileType.DIRECTORY || !change.file.exists()) {
//                    return
//                }
//                if (!change.file.name.matches("R\\.class") && !change.file.name.matches("R\\\$.*\\.class")) {
//                    File dir = classesDirs.find { change.file.path.startsWith(it.path) }
//                    Set<File> files = classesToUpdateInfo[dir]
//                    if (files == null) {
//                        files = []
//                        classesToUpdateInfo[dir] = files
//                    }
//                    files.add(change.file)
//                }
//            }
//        }
//
//    }

    void configure(ApplicationVariantImpl applicationVariant, HostExtension hostExtension, CustomTransform customTransform) {
        this.variantName = applicationVariant.name
        this.hostExtension = hostExtension
        this.customTransform = customTransform
        /*Note classOutput dir can't output to outputDir, because class will change in sub project
        and it will cause the task can't run in incremental, so we should change outputDir to 'tmp'
        */
        this.classOutputDir = new File(this.outputDir, "classes")
        if (!this.classOutputDir.exists()) {
            this.classOutputDir.mkdirs()
        }
        this.outputDir = new File(this.outputDir, "tmp")
        if (!this.outputDir.exists()) {
            this.outputDir.mkdirs()
        }
    }
}
