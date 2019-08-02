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

import com.android.build.api.transform.Context
import com.android.build.gradle.internal.LoggerWrapper
import com.android.build.gradle.internal.api.ApplicationVariantImpl
import com.android.build.gradle.internal.scope.VariantScope
import com.android.builder.core.AndroidBuilder
import com.android.dex.DexIndexOverflowException
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.incremental.IncrementalTaskInputs
import com.android.build.api.transform.TransformException;
import com.android.dex.Dex
import com.android.dex.ClassDef
import com.android.dx.command.dexer.DxContext
import com.android.dx.merge.CollisionPolicy
import com.android.dx.merge.DexMerger
import com.android.ide.common.blame.Message
import com.android.ide.common.blame.MessageReceiver;
import com.android.ide.common.blame.ParsingProcessOutputHandler;
import com.android.ide.common.blame.parser.DexParser;
import com.android.ide.common.blame.parser.ToolOutputParser
import com.android.ide.common.process.ProcessOutput;
import com.android.ide.common.process.ProcessOutputHandler
import org.gradle.api.Project
import org.gradle.api.file.FileTree
import org.gradle.util.VersionNumber
import org.gradle.workers.WorkerExecutor
import com.android.build.gradle.BaseExtension

import javax.inject.Inject
import java.nio.file.Files

/**
 * The 'CustomDexTask' workflow
 * 1. Extract classes that belong the project from the original secondly dex and merge them into a new dex;
 * 2. Generate classes list files according to the dex;
 * 3. Divide the classes according the classes list files
 * 3. Dex the divided classes into new dex separately
 * 4. merge the original dex with new dex
 * 5. If DexIndexOverflowException occur when merge the main dex, try to split the main dex into two, then try to merge again
 */

class CustomDexTask extends DefaultTask implements Context {
    private static final String classListShouldUpdateFileNameSuffix = "classes_list_should_update_"
    private final WorkerExecutor workerExecutor
    private String variantName
    private ProcessOutputHandler outputHandler
    HostExtension hostExtension
    BaseExtension baseExtension
    File dexInfoDir
    final LoggerWrapper loggerWrapper = LoggerWrapper.getLogger(CustomDexTask.class)

    ClassToDex classToDex

    @InputFiles
    List<File> classesDirs

    @OutputDirectory
    File outputDir

    @Inject
    CustomDexTask(WorkerExecutor workerExecutor) {
        super()
        this.workerExecutor = workerExecutor
    }

    @TaskAction
    void execute(IncrementalTaskInputs inputs) {
        if (!inputs.incremental) {
            //Should clean dex because there may contain new dex that not belong to host apk
            dexInfoDir.deleteDir()
            dexInfoDir.mkdirs()
            outputDir.deleteDir()
            outputDir.mkdirs()
            extractFilesFromHostApk()
        }
        if (!hostExtension.updateJavaClass) {
            return
        }

        def classesToUpdateInfo = [:] // map [dir, Set<classes>]
        if (!inputs.incremental) {
            classesDirs.each { dir ->
                classesToUpdateInfo[dir] = project.fileTree(dir).exclude("**/R.class", "**/R\$*.class").files
            }
            generateSecondlyDexToUpdate(classesToUpdateInfo)
        } else {
            inputs.outOfDate { change ->
                if (!change.file.name.matches("R\\.class") && !change.file.name.matches("R\\\$.*\\.class")) {
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

        FileTree dexes = project.fileTree(outputDir).include("*.dex")
        ProcessOutput output
        Closeable ignored = output = outputHandler.createOutput()
        DxContext dxContext = new DxContext(output.getStandardOutput(), output.getErrorOutput())
        List<DexInfo> dexInfos = []

        // Copy the classes should to update to the appropriate directory
        dexes.each { dexFile ->
            String dexName = dexFile.name.substring(0, dexFile.name.length() - 4)
            File classesListShouldUpdateFile = new File(dexInfoDir, "${classListShouldUpdateFileNameSuffix}${dexName}.txt")
            if (classesListShouldUpdateFile.exists()) {
                DexInfo dexInfo = new DexInfo()
                dexInfo.dstDex = dexFile
                dexInfo.classesToUpdateDir = new File(dexInfoDir, dexName)
                dexInfo.classesToUpdateDir.deleteDir()
                dexInfo.classesToUpdateDir.mkdirs()
                dexInfos.add(dexInfo)
                classesListShouldUpdateFile.eachLine { className ->
                    classesToUpdateInfo.find { k, v ->
                        File classFile = new File(k, className)
                        File linkFile = new File(dexInfo.classesToUpdateDir, className)
                        Set<File> files = v
                        if (files.contains(classFile) && classFile.exists()) {
                            files.remove(classFile)
                            dexInfo.needUpdate = true
                            if (!linkFile.parentFile.exists()) linkFile.parentFile.mkdirs()
                            if (!linkFile.exists()) {
                                Files.createSymbolicLink(linkFile.toPath(), classFile.toPath())
                            }
                            return true
                        }
                    }
                }
            }
        }

        // Copy remaining class to the main dex
        File mainDexFile = new File(outputDir, "classes.dex")
        DexInfo mainDexInfo = new DexInfo()
        mainDexInfo.dstDex = mainDexFile
        mainDexInfo.classesToUpdateDir = new File(dexInfoDir, "classes")
        mainDexInfo.classesToUpdateDir.deleteDir()
        mainDexInfo.classesToUpdateDir.mkdirs()
        dexInfos.add(mainDexInfo)
        classesToUpdateInfo.each { k, v ->
            v.each { File f ->
                String className = f.path.substring(k.path.length())
                File linkFile = new File(mainDexInfo.classesToUpdateDir, className)
                mainDexInfo.needUpdate = true
                if (!linkFile.parentFile.exists()) linkFile.parentFile.mkdirs()
                if (!linkFile.exists()) {
                    Files.createSymbolicLink(linkFile.toPath(), f.toPath())
                }
            }
        }

        dexInfos.each { dexInfo ->
            if (dexInfo.needUpdate) {
                try {
                    mergeClassesToDex(dexInfo.classesToUpdateDir, dexInfo.dstDex, dxContext)
                } catch (DexIndexOverflowException e) {
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
                            R8Adapter.splitDex(dexFile, mainDexClassesListFile, splitOutputDir)
                            File newSecondlyDexFile = new File(outputDir, "classes${dexes.size() + 1}.dex")
                            File splitDexFile = new File(splitOutputDir, "classes2.dex")
                            File newMainDexFile = new File(splitOutputDir, "classes.dex")
                            if (splitDexFile.exists()) {
                                Dex dex = new Dex(splitDexFile)
                                File newClassesDir = new File(dexInfoDir, "classes${dexes.size() + 1}")
                                File classesListShouldUpdateFile = new File(dexInfoDir, "${classListShouldUpdateFileNameSuffix}classes${dexes.size() + 1}.txt")
                                def pw = new PrintWriter(classesListShouldUpdateFile.newWriter(false))
                                for (ClassDef classDef : dex.classDefs()) {
                                    String typeName = dex.typeNames().get(classDef.typeIndex)
                                    typeName = typeName.substring(1, typeName.length() - 1) + ".class"
                                    pw.println(typeName)
                                    File classFile = new File(dexInfo.classesToUpdateDir, typeName)
                                    File newClassFile = new File(newClassesDir, typeName)
                                    if (classFile.exists()) {
                                        classFile.renameTo(newClassFile)
                                    }
                                }
                                pw.flush()
                                pw.close()
                                FileTree classes = project.fileTree(newClassesDir).include("**/**.class")
                                if (classes.size() > 0) {
                                    mergeClassesToDex(newClassesDir, splitDexFile, dxContext)
                                }
                                classes = project.fileTree(dexInfo.classesToUpdateDir).include("**/**.class")
                                if (classes.size() > 0) {
                                    mergeClassesToDex(dexInfo.classesToUpdateDir, newMainDexFile, dxContext)
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
                } catch (Exception e) {
                    throw new TransformException(e)
                }
            }
        }
        output.close()
    }

    @Override
    String getVariantName() {
        return variantName
    }

    @Override
    WorkerExecutor getWorkerExecutor() {
        return workerExecutor
    }

    void mergeClassesToDex(File classesDir, File dexFile, DxContext dxContext) {
        //Clean dex dir before dex
        File dexOutputDir = new File(dexInfoDir, "dex")
        dexOutputDir.deleteDir()
        dexOutputDir.mkdirs()
        // class to dex
        classToDex.classToDex(classesDir, dexOutputDir)
        // merge dex
        FileTree dexesToUpdate = project.fileTree(dexOutputDir).include("**/*.dex")
        boolean dstDexExists = dexFile.exists()
        int dexesToMergeSize = dstDexExists ? (dexesToUpdate.size() + 1) : dexesToUpdate.size()
        if (dexesToMergeSize > 1) {
            Dex[] dexesToMerge = new Dex[dexesToMergeSize]
            int index = 0
            dexesToUpdate.each {
                dexesToMerge[index] = new Dex(it)
                index++
            }
            if (dstDexExists) {
                dexesToMerge[index] = new Dex(dexFile)
            }
            DexMerger dexMerger =
                    new DexMerger(
                            dexesToMerge,
                            CollisionPolicy.KEEP_FIRST,
                            dxContext)
            Dex dexMerge = dexMerger.merge()
            Files.write(dexFile.toPath(), dexMerge.getBytes())
        } else {
            project.copy {
                from dexesToUpdate.singleFile
                into dexFile.parentFile
                rename dexesToUpdate.singleFile.name, dexFile.name
            }
        }
    }

    boolean generateMainDexClassesList(File mainDexClassesListFile, File dexFile) {
        File mainDexClassesRulesFile = new File(baseExtension.sdkDirectory, "build-tools/${baseExtension.buildToolsVersion}/mainDexClasses.rules")
        File mainDexClassesNoAaptRulesFile = new File(baseExtension.sdkDirectory, "build-tools/${baseExtension.buildToolsVersion}/mainDexClassesNoAapt.rules")
        File androidJarFile = new File(baseExtension.sdkDirectory, "platforms/${baseExtension.compileSdkVersion}/android.jar")
        if (!androidJarFile.exists()) {
            println(" can't generate main dex classes list because file: " + androidJarFile + " not exist")
            return false
        }
        if (!mainDexClassesRulesFile.exists() && !mainDexClassesNoAaptRulesFile.exists()) {
            println(" can't generate main dex classes list because no rules file in : " + mainDexClassesRulesFile.parentFile)
            return false
        }

        List<File> rules = []
        if (mainDexClassesRulesFile.exists()) {
            rules.add(mainDexClassesRulesFile)
        }
        if (mainDexClassesNoAaptRulesFile.exists()) {
            rules.add(mainDexClassesNoAaptRulesFile)
        }
        R8Adapter.generateMainDexList(dexFile, androidJarFile, rules, mainDexClassesListFile)
        return true
    }

    void extractFilesFromHostApk() {
        // extract dex and manifest from host apk
        if (hostExtension.updateJavaClass) {
            project.copy {
                from project.zipTree(this.hostExtension.hostApk)
                into this.outputDir
                include "*.dex"
                include "AndroidManifest.xml"
            }
        } else {
            project.copy {
                from project.zipTree(this.hostExtension.hostApk)
                into this.outputDir
                include "AndroidManifest.xml"
            }
        }

        if (hostExtension.modifyApkDebuggable) {
            ManifestEditor.modifyAndroidManifestToDebuggable(new File(outputDir, "AndroidManifest.xml"))
        }
    }

    void generateSecondlyDexToUpdate(def classesToUpdateInfo) {
        FileTree dexes = project.fileTree(outputDir).include("*.dex")
        File secondlyDexFileToUpdate = new File(outputDir, "classes${dexes.size() + 1}.dex")
        File classesListShouldUpdateFile = new File(dexInfoDir, "${classListShouldUpdateFileNameSuffix}classes${dexes.size() + 1}.txt")
        def classesListShouldUpdatePw = new PrintWriter(classesListShouldUpdateFile.newWriter(false))
        boolean shouldSplitDex = false
        File mainDexFile = new File(outputDir, "classes.dex")
        if (dexes.size() > 1) {
            Dex mainDex = new Dex(mainDexFile)
            classesToUpdateInfo.each { dir, Files ->
                Files.each { File f ->
                    String fTypeName = f.path.substring(dir.path.length() + 1, f.path.length() - 6).replace("\\", "/")
                    boolean isInMainDex = false
                    for (ClassDef classDef : mainDex.classDefs()) {
                        String typeName = mainDex.typeNames().get(classDef.typeIndex)
                        typeName = typeName.substring(1, typeName.length() - 1)
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
        }
        classesListShouldUpdatePw.flush()
        classesListShouldUpdatePw.close()
        if (shouldSplitDex) {
            File splitDir = new File(dexInfoDir, "split")
            final List<File> dexShouldMerge = []
            dexes.each { dexFile ->
                if (dexFile.name != "classes.dex") {
                    File splitOutputDir = new File(splitDir, dexFile.name)
                    splitOutputDir.deleteDir()
                    splitOutputDir.mkdirs()
                    R8Adapter.splitDex(dexFile, classesListShouldUpdateFile, splitOutputDir)
                    File newSecondlyDex = new File(splitOutputDir, "classes2.dex")
                    if (newSecondlyDex.exists()) {
                        dexShouldMerge.add(new File(splitOutputDir, "classes.dex"))
                        project.copy {
                            from newSecondlyDex
                            into outputDir
                            rename newSecondlyDex.name, dexFile.name
                        }
                    }
                }
            }

            if (dexShouldMerge.size() > 1) {
                Dex[] dexesToMerge = new Dex[dexShouldMerge.size()]
                int index = 0
                dexShouldMerge.each {
                    dexesToMerge[index] = new Dex(it)
                    index++
                }
                DexMerger dexMerger =
                        new DexMerger(
                                dexesToMerge,
                                CollisionPolicy.FAIL,
                                dxContext)
                Dex dexMerge = dexMerger.merge()
                Files.write(secondlyDexFileToUpdate.toPath(), dexMerge.getBytes())
            } else if (dexShouldMerge.size() == 1) {
                dexShouldMerge.get(0).renameTo(secondlyDexFileToUpdate)
            } else {
                // Classes is new class that should in main dex
                classesListShouldUpdateFile.delete()
            }
        }
    }

    void configure(Project project, ApplicationVariantImpl applicationVariant, HostExtension hostExtension) {
        this.variantName = applicationVariant.name
        this.hostExtension = hostExtension
        this.dexInfoDir = new File(project.buildDir, "debughelp/hostDexInfo")
        if (!this.dexInfoDir.exists()) {
            this.dexInfoDir.mkdirs()
        }
        this.baseExtension = project.android
        if (hostExtension.updateJavaClass) {
            MessageReceiver errorReporter
            VariantScope scope = applicationVariant.variantData.scope
            AndroidBuilder androidBuilder = scope.getGlobalScope().androidBuilder
            String curVersionString = Utils.androidGradleVersion()
            VersionNumber currentVersion = VersionNumber.parse(curVersionString)
            VersionNumber miniVersion = VersionNumber.parse("3.1.0")
            if (currentVersion >= miniVersion) {
                errorReporter = androidBuilder.getMessageReceiver()
            } else {
                errorReporter = androidBuilder.getErrorReporter()
            }
            outputHandler =
                    new ParsingProcessOutputHandler(
                            new ToolOutputParser(new DexParser(), Message.Kind.ERROR, loggerWrapper),
                            new ToolOutputParser(new DexParser(), loggerWrapper),
                            errorReporter);

            miniVersion = VersionNumber.parse("3.3.0")
            if (currentVersion >= miniVersion) {
                classToDex = new ClassToDex330(this, project, applicationVariant)
            } else {
                classToDex = new ClassToDex300(project, applicationVariant, outputHandler)
            }
        }
    }

    private class DexInfo {
        File srcDex
        File dstDex
        File classesToUpdateDir
        boolean needUpdate = false
    }
}
