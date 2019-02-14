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

import javax.inject.Inject
import java.nio.file.Files

class CustomDexTask extends DefaultTask implements Context {
    private final WorkerExecutor workerExecutor
    private String variantName
    private ProcessOutputHandler outputHandler
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
        FileTree dexes = project.fileTree(outputDir).include("*.dex")
        File dexesInfoDir = new File(project.buildDir, "intermediates/hostDexInfo")
        if (!dexesInfoDir.exists()) {
            dexesInfoDir.mkdirs()
        }

        List<DexInfo> dexInfos = []
        def classesToUpdateInfo = [:]
        if (!inputs.incremental) {
            classesDirs.each {
                classesToUpdateInfo[it] = project.fileTree(it).exclude("**/R.class", "**/R\$*.class").files
            }
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

        if (dexes.size() > 1) {
            dexes.each {
                DexInfo dexInfo = new DexInfo()
                dexInfo.dstDex = it
                dexInfo.classesToUpdateDir = new File(dexesInfoDir, it.name.substring(0, it.name.length() - 4))
                if (!dexInfo.classesToUpdateDir.exists()) dexInfo.classesToUpdateDir.mkdirs()
                project.delete(dexInfo.classesToUpdateDir.listFiles())
                dexInfos.add(dexInfo)
                File dexInfoFile = new File(dexesInfoDir, "${it.name}.info")
                if (dexInfoFile.exists()) {
                    dexInfoFile.eachLine { className ->
                        classesToUpdateInfo.find { k, v ->
                            File classFile = new File(k, "${className}.class")
                            File linkFile = new File(dexInfo.classesToUpdateDir, "${className}.class")
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
                } else {
                    Dex dex = new Dex(it)
                    def pw = new PrintWriter(dexInfoFile.newWriter(false))
                    for (ClassDef classDef : dex.classDefs()) {
                        String typeName = dex.typeNames().get(classDef.typeIndex)
                        typeName = typeName.substring(1, typeName.length() - 1)
                        pw.println(typeName)
                        classesToUpdateInfo.find { k, v ->
                            File classFile = new File(k, "${typeName}.class")
                            File linkFile = new File(dexInfo.classesToUpdateDir, "${typeName}.class")
                            Set<File> files = v
                            if (files.contains(classFile) && classFile.exists()) {
                                dexInfo.needUpdate = true
                                files.remove(classFile)
                                if (!linkFile.parentFile.exists()) linkFile.parentFile.mkdirs()
                                if (!linkFile.exists()) {
                                    Files.createSymbolicLink(linkFile.toPath(), classFile.toPath())
                                }
                                return true
                            }
                        }
                    }
                    pw.flush()
                    pw.close()
                }
            }

        } else {
            DexInfo dexInfo = new DexInfo()
            dexInfo.dstDex = dexes.singleFile
            dexInfo.srcDex = new File(dexesInfoDir, "classes.dex")
            dexInfo.classesToUpdateDir = new File(dexesInfoDir, "classes")
            dexInfos.add(dexInfo)
            project.delete(dexInfo.classesToUpdateDir.listFiles())
        }

        // Copy remaining class to the main dex
        DexInfo mainDexInfo = dexInfos.find { it.dstDex.name == "classes.dex" }
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

        File dexOuputDir = new File(dexesInfoDir, "dex")
        if (!dexOuputDir.exists()) dexOuputDir.mkdirs()
        project.delete(dexOuputDir.listFiles())

        ProcessOutput output
        Closeable ignored = output = outputHandler.createOutput()
        DxContext dxContext = new DxContext(output.getStandardOutput(), output.getErrorOutput())
        dexInfos.each {
            if (it.needUpdate) {
                try {
                    // class to dex
                    classToDex.classToDex(it.classesToUpdateDir, dexOuputDir)
                    // merge dex
                    FileTree dexesToUpdate = project.fileTree(dexOuputDir).include("**/*.dex")
                    Dex[] dexesToMerge = new Dex[dexesToUpdate.size() + 1]
                    int index = 0
                    dexesToUpdate.each {
                        dexesToMerge[index] = new Dex(it)
                        index++
                    }
                    dexesToMerge[index] = new Dex(it.dstDex)
                    DexMerger dexMerger =
                            new DexMerger(
                                    dexesToMerge,
                                    CollisionPolicy.KEEP_FIRST,
                                    dxContext)
                    Dex dexMerge = dexMerger.merge()
                    Files.write(it.dstDex.toPath(), dexMerge.getBytes())
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

    void configure(Project project, ApplicationVariantImpl applicationVariant) {
        variantName = applicationVariant.name
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
        }else {
            classToDex = new ClassToDex300(project, applicationVariant, outputHandler)
        }
    }
}
