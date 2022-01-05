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
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.incremental.IncrementalTaskInputs
import com.android.build.api.transform.TransformException;
import com.android.ide.common.blame.MessageReceiver;
import org.gradle.api.Project
import org.gradle.api.file.FileTree
//import org.gradle.work.InputChanges
import org.gradle.workers.WorkerExecutor
import com.android.build.gradle.BaseExtension

import javax.inject.Inject

/**
 * The 'DexUpdateTask' workflow
 * 1. Extract classes that belong the project from the original secondly dex and merge them into a new dex;
 * 2. Generate classes list files according to the dex;
 * 3. Divide the classes according the classes list files
 * 3. Dex the divided classes into new dex separately
 * 4. Merge the original dex with new dex
 * 5. If DexIndexOverflowException occur when merge the main dex, try to split the main dex into two, then try to merge again
 */

class DexUpdateTask extends DefaultTask {
    private static final String classListShouldUpdateFileNameSuffix = "classes_list_should_update_"

    private final WorkerExecutor workerExecutor

    @Internal
    File dexMergeDir

    @Internal
    MessageReceiver messageReceiver

    @Internal
    int minApiLevel

    @Internal
    HostExtension hostExtension
    @Internal
    BaseExtension baseExtension
    @Internal
    File dexInfoDir

    @InputDirectory
    File dexDirToUpdate

    @OutputDirectory
    File outputDir

    @Inject
    DexUpdateTask(WorkerExecutor workerExecutor) {
        super()
        this.workerExecutor = workerExecutor
    }

    @TaskAction
    void execute(IncrementalTaskInputs inputs) {
        if (!inputs.incremental) {
            //Should clean dex because there may contain new dex that not belong to host apk
            dexInfoDir.deleteDir()
            dexInfoDir.mkdirs()
        }
        if (!hostExtension.updateJavaClass) {
            return
        }

        com.yy.android.gradle.debug.DexParser dexParser = GradleApiAdapter.createDexParser(project, minApiLevel)
        com.yy.android.gradle.debug.DexMerger dexMerger = GradleApiAdapter.createDexMerger(messageReceiver, dexMergeDir, minApiLevel)

        try {
            Set<File> dexesToUpdate = []
            if (!inputs.incremental) {
                dexesToUpdate = project.fileTree(dexDirToUpdate).include("**/*.dex").files
                generateSecondlyDexToUpdate(dexesToUpdate, dexMerger)
            } else {
                inputs.outOfDate { change ->
                    if (change.file.isFile() && change.file.name.length() > 4 && change.file.name.substring(change.file.name.length() - 4) == ".dex") {
                        dexesToUpdate.add(change.file)
                    }
                }
            }

            FileTree dexes = project.fileTree(outputDir).include("*.dex")
            List<DexInfo> dexInfos = []

            // Distribute classes to corresponding dex
            dexes.each { dexFile ->
                String dexName = dexFile.name.substring(0, dexFile.name.length() - 4)
                File classesListShouldUpdateFile = new File(dexInfoDir, "${classListShouldUpdateFileNameSuffix}${dexName}.txt")
                if (classesListShouldUpdateFile.exists() && !dexesToUpdate.isEmpty()) {
                    DexInfo dexInfo = new DexInfo()
                    dexInfo.dstDex = dexFile
                    dexInfos.add(dexInfo)
                    classesListShouldUpdateFile.eachLine { className ->
                        //replace ".class" to ".dex"
                        className = className.substring(0, className.length() - 6) + ".dex"
                        def result = dexesToUpdate.find { it.path.substring(dexDirToUpdate.path.length() + 1) == className }
                        if (result != null) {
                            dexInfo.dexesToUpdate.add(result)
                            dexesToUpdate.remove(result)
                            dexInfo.needUpdate = true
                        }
                    }
                }
            }

            // Distribute remaining class to the main dex
            if (!dexesToUpdate.isEmpty()) {
                File mainDexFile = new File(outputDir, "classes.dex")
                DexInfo mainDexInfo = new DexInfo()
                mainDexInfo.dstDex = mainDexFile
                mainDexInfo.needUpdate = true
                mainDexInfo.dexesToUpdate.addAll(dexesToUpdate)
                dexInfos.add(mainDexInfo)
            }

            dexInfos.each { dexInfo ->
                if (dexInfo.needUpdate) {
                    com.yy.android.gradle.debug.DexMerger.Result result
                    result = mergeClassesToDex(dexMerger, dexInfo.dexesToUpdate, dexInfo.dstDex)
                    if (result == com.yy.android.gradle.debug.DexMerger.Result.OVER_FLOW) {
                        File mainDexClassesListFile = new File(dexInfoDir, "main_dex_classes_list.txt")
                        if (dexInfo.dstDex.name == "classes.dex" && !mainDexClassesListFile.exists()) {
                            println("Try to split main dex")
                            //Try to split the main dex if have not split yet
                            File dexFile = new File(outputDir, "classes.dex")
                            if (generateMainDexClassesList(mainDexClassesListFile, dexFile)) {
                                File splitDir = new File(dexInfoDir, "split")
                                File splitOutputDir = new File(splitDir, dexFile.name)
                                splitOutputDir.deleteDir()
                                splitOutputDir.mkdirs()
                                GradleApiAdapter.splitDex(dexFile, mainDexClassesListFile, splitOutputDir)
                                File newSecondlyDexFile = new File(outputDir, "classes${dexes.size() + 1}.dex")
                                File splitDexFile = new File(splitOutputDir, "classes2.dex")
                                File newMainDexFile = new File(splitOutputDir, "classes.dex")
                                if (splitDexFile.exists()) {
                                    Set<String> classDefs = dexParser.getTypeList(splitDexFile)
                                    File classesListShouldUpdateFile = new File(dexInfoDir, "${classListShouldUpdateFileNameSuffix}classes${dexes.size() + 1}.txt")
                                    def pw = new PrintWriter(classesListShouldUpdateFile.newWriter(false))
                                    Set<File> dexesToMergeToSplitDexFile = []
                                    for (String typeName : classDefs) {
                                        pw.println(typeName + ".class")
                                        File classFile = dexInfo.dexesToUpdate.find { File it -> it.path.substring(dexDirToUpdate.path.length() + 1) == (typeName + ".dex") }
                                        if (classFile != null) {
                                            dexesToMergeToSplitDexFile.add(classFile)
                                            dexInfo.dexesToUpdate.remove(classFile)
                                        }
                                    }
                                    pw.flush()
                                    pw.close()
                                    if (!dexesToMergeToSplitDexFile.isEmpty()) {
                                        mergeClassesToDex(dexMerger, dexesToMergeToSplitDexFile, splitDexFile)
                                    }
                                    if (!dexInfo.dexesToUpdate.isEmpty()) {
                                        mergeClassesToDex(dexMerger, dexInfo.dexesToUpdate, newMainDexFile)
                                    }
                                    newSecondlyDexFile.delete()
                                    splitDexFile.renameTo(newSecondlyDexFile)
                                    dexInfo.dstDex.delete()
                                    newMainDexFile.renameTo(dexInfo.dstDex)
                                } else {
                                    //Can't split dex
                                    throw new TransformException("You have add too much classes to update and exceed the limitation 65536")
                                }
                            } else if (!mainDexClassesListFile.exists()) {
                                mainDexClassesListFile.createNewFile()
                            }
                        } else {
                            throw new TransformException("You have add too much classes to update and exceed the limitation 65536")
                        }
                    } else if (result == com.yy.android.gradle.debug.DexMerger.Result.FAILED) {
                        throw new TransformException(("Update main dex failed"))
                    }
                }
            }
        }finally {
            dexMerger.close()
        }
    }

    com.yy.android.gradle.debug.DexMerger.Result mergeClassesToDex(com.yy.android.gradle.debug.DexMerger dexMerger, Set<File> dexesToUpdate, File dexFile) {
        if (!dexesToUpdate.isEmpty()) {
            return dexMerger.merge(dexesToUpdate, dexFile, true)
        }else {
            return com.yy.android.gradle.debug.DexMerger.Result.FAILED
        }
    }

    boolean generateMainDexClassesList(File mainDexClassesListFile, File dexFile) {
        File mainDexClassesRulesFile = new File(baseExtension.sdkDirectory, "build-tools/${baseExtension.buildToolsVersion}/mainDexClasses.rules")
        File mainDexClassesNoAaptRulesFile = new File(baseExtension.sdkDirectory, "build-tools/${baseExtension.buildToolsVersion}/mainDexClassesNoAapt.rules")
        File androidJarFile = new File(baseExtension.sdkDirectory, "platforms/${baseExtension.compileSdkVersion}/android.jar")
        if (!androidJarFile.exists()) {
            println("can't generate main dex classes list because file: " + androidJarFile + " not exist")
            return false
        }
        if (!mainDexClassesRulesFile.exists() && !mainDexClassesNoAaptRulesFile.exists()) {
            println("can't generate main dex classes list because no rules file in : " + mainDexClassesRulesFile.parentFile)
            return false
        }

        List<File> rules = []
        if (mainDexClassesRulesFile.exists()) {
            rules.add(mainDexClassesRulesFile)
        }
        if (mainDexClassesNoAaptRulesFile.exists()) {
            rules.add(mainDexClassesNoAaptRulesFile)
        }
        GradleApiAdapter.generateMainDexList(dexFile, androidJarFile, rules, mainDexClassesListFile)
        return true
    }


    void generateSecondlyDexToUpdate(def dexesToUpdate , DexMerger dexMerger) {
        FileTree dexes = project.fileTree(outputDir).include("*.dex")
        int originalDexSize = dexes.size()
        int secondlyDexIndex  = originalDexSize + 1
        File classesListShouldUpdateFile = new File(dexInfoDir, "classesListShouldUpdate.txt")
        def classesListShouldUpdatePw = new PrintWriter(classesListShouldUpdateFile.newWriter(false))
        boolean shouldSplitDex = false
        File mainDexFile = new File(outputDir, "classes.dex")
        com.yy.android.gradle.debug.DexParser dexParser = GradleApiAdapter.createDexParser(project, minApiLevel)
        if (dexes.size() > 1) {
            Set<String> mainClassDefs = dexParser.getTypeList(mainDexFile)
            dexesToUpdate.each { File f ->
                String fTypeName = f.path.substring(dexDirToUpdate.path.length() + 1, f.path.length() - 4).replace("\\", "/")
                boolean isInMainDex = false
                for (String typeName : mainClassDefs) {
                    if (typeName == fTypeName) {
                        isInMainDex = true
                        break
                    }
                }
                if (!isInMainDex) {
                    classesListShouldUpdatePw.println(fTypeName + ".class")
                    shouldSplitDex = true
                }
            }
        }
        classesListShouldUpdatePw.flush()
        classesListShouldUpdatePw.close()

        if (shouldSplitDex) {
            File splitDir = new File(dexInfoDir, "split")
            final Set<File> dexShouldMerge = []
            dexes.each { dexFile ->
                if (dexFile.name != "classes.dex") {
                    File splitOutputDir = new File(splitDir, dexFile.name)
                    splitOutputDir.deleteDir()
                    splitOutputDir.mkdirs()
                    GradleApiAdapter.splitDex(dexFile, classesListShouldUpdateFile, splitOutputDir)
                    File newSecondlyDex = new File(splitOutputDir, "classes2.dex")
                    if (newSecondlyDex.exists()) {
                        dexShouldMerge.add(new File(splitOutputDir, "classes.dex"))
                        project.copy {
                            from newSecondlyDex
                            into outputDir
                            rename newSecondlyDex.name, dexFile.name
                        }
                    }else {
                        Set<String> classDefs = dexParser.getTypeList(new File(splitOutputDir, "classes.dex"))
                        String typeName = classDefs[0]
                        def result = classesListShouldUpdateFile.find { String line ->
                            return line == typeName
                        }
                        // the dexFile only contain the classes should update
                        if (result) {
                            dexShouldMerge.add(new File(splitOutputDir, "classes.dex"))
                            dexFile.delete()
                            secondlyDexIndex--
                        }
                    }
                }
            }

            if (originalDexSize  > secondlyDexIndex) {
                //update dexes
                int dexIndex = 2
                dexes.each { dexFile ->
                    if (dexFile.name != "classes.dex") {
                        dexFile.renameTo(new File(dexFile.parentFile, "classes${dexIndex}.dex"))
                        dexIndex++
                    }
                }
                for (int i = (secondlyDexIndex + 1); i < (originalDexSize + 1); i++) {
                    hostExtension.filesShouldDelete.add("classes${i}.dex")
                }
            }

            File secondlyDexFileToUpdate = new File(outputDir, "classes${secondlyDexIndex}.dex")
            classesListShouldUpdateFile.renameTo(new File(dexInfoDir, "${classListShouldUpdateFileNameSuffix}classes${secondlyDexIndex}.txt"))

            if (dexShouldMerge.size() > 1) {
                DexMerger.Result result = dexMerger.merge(dexShouldMerge, secondlyDexFileToUpdate, false)
                if(result != DexMerger.Result.SUCCEED) {
                    println("DexUpdateTask merge secondly dexes failed " + result)
                }
            } else if (dexShouldMerge.size() == 1) {
                dexShouldMerge[0].renameTo(secondlyDexFileToUpdate)
            } else {
                // Classes is new class that should in main dex
                classesListShouldUpdateFile.delete()
            }
        }
    }

    void configure(Project project, ApplicationVariantImpl applicationVariant, HostExtension hostExtension, int minApiLevel) {
        this.hostExtension = hostExtension
        this.dexInfoDir = new File(project.buildDir, "debughelp/hostDexInfo")
        if (!this.dexInfoDir.exists()) {
            this.dexInfoDir.mkdirs()
        }
        this.dexMergeDir = new File(project.buildDir, "debughelp/dexMerge")
        this.baseExtension = project.android
        this.minApiLevel = minApiLevel
        this.messageReceiver = GradleApiAdapter.getMessageReceiver(applicationVariant, project)
    }

    private class DexInfo {
        File srcDex
        File dstDex
        Set<File> dexesToUpdate = []
        boolean needUpdate = false
    }
}
