/*
 * Copyright 2015-present wequick.net
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
package com.yy.android.gradle.debug;

import com.android.build.api.transform.Context;
import com.android.build.api.transform.QualifiedContent.ContentType;
import com.android.build.api.transform.QualifiedContent.Scope;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformOutputProvider
import com.android.build.gradle.internal.LoggerWrapper;
import com.android.builder.sdk.TargetInfo

import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.api.ApplicationVariantImpl

import com.android.builder.core.AndroidBuilder
import com.android.builder.core.DexByteCodeConverter
import com.android.builder.core.DexOptions
import com.android.builder.dexing.DexingType
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
import com.android.ide.common.process.ProcessOutputHandler;

import org.gradle.api.file.FileCollection
import org.gradle.api.Project;
import org.gradle.api.Task
import org.gradle.api.file.FileTree
import org.gradle.util.VersionNumber

import java.nio.file.Files

class HostCustomMultiDexTransform extends Transform {
    private static
    final LoggerWrapper logger = LoggerWrapper.getLogger(HostCustomMultiDexTransform.class)

    private static final String createPrefix = "Android Gradle "

    @Override
    String getName() {
        return "hostCustomMultiDex"
    }

    @Override
    Set<ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS;
    }

    @Override
    Set<Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT;
    }

    @Override
    boolean isIncremental() {
        return false;
    }

    @Override
    void transform(Context context, Collection<TransformInput> inputs,
                   Collection<TransformInput> referencedInputs,
                   TransformOutputProvider outputProvider, boolean isIncremental)
            throws IOException, TransformException, InterruptedException {

        inputs.each {
            // Bypass the directories
            it.directoryInputs.each { inputDir ->
                //File dest = outputProvider.getContentLocation(
                //       inputDir.name, inputDir.contentTypes, inputDir.scopes, Format.DIRECTORY)
                //FileUtils.copyDirectory(inputDir.file, dest)
            }

            // Filter the jars
            it.jarInputs.each {
                File src = it.file

                //File dest = outputProvider.getContentLocation(
                //       it.name, it.contentTypes, it.scopes, Format.JAR)
                //FileUtils.copyFile(it.file, dest)
            }
        }
        Project project = ((Task) context).project
        String taskName = ((Task) context).name
        String taskNamePrefix = "transformClassesWithHostCustomMultiDexFor"
        String variantName = taskName.substring(taskNamePrefix.length())
        if (variantName.indexOf("Debug") == -1) {
            return
        }

        HostExtension fastDebug = project.fastDebug
        if (fastDebug.updateJavaClass) {
            File dexesInfoDir = new File(project.buildDir, "intermediates/hostDexInfo")
            if (!dexesInfoDir.exists()) {
                dexesInfoDir.mkdirs()
            }

            // 1.Split classes to different directory according to the host dex
            def classesToUpdateInfo = [:]
            ApplicationVariantImpl variant = project.android.applicationVariants.find {
                it.name.capitalize() == variantName
            }

            DependencyUtils.collectDependencyProjectClasses(project, variant.name, classesToUpdateInfo)
            File unzipHostApk = new File(project.buildDir, "hostApk")
            FileTree dexes = project.fileTree(unzipHostApk).include("*.dex")

            List<DexInfo> dexInfos = []
            if (dexes.size() > 1) {
                dexes.each {
                    DexInfo dexInfo = new DexInfo()
                    dexInfo.dstDex = it
                    if (it.name == "classes.dex") {
                        dexInfo.srcDex = new File(dexesInfoDir, "classes0.dex")
                    } else {
                        dexInfo.srcDex = new File(dexesInfoDir, "${it.name}")
                    }
                    dexInfo.classesToUpdateDir = new File(dexesInfoDir, it.name.substring(0, it.name.length() - 4))
                    if (!dexInfo.classesToUpdateDir.exists()) dexInfo.classesToUpdateDir.mkdirs()
                    dexInfos.add(dexInfo)
                    File dexInfoFile = new File(dexesInfoDir, "${it.name}.info")
                    if (dexInfoFile.exists()) {
                        dexInfoFile.eachLine { className ->
                            classesToUpdateInfo.find { k, v ->
                                File classFile = new File(k, "${className}.class")
                                File linkFile = new File(dexInfo.classesToUpdateDir, "${className}.class")
                                if (classFile.exists()) {
                                    Set<File> files = v
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
                                if (classFile.exists()) {
                                    Set<File> files = v
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

            // 2.Dex classes that need to update and merge with host dex
            VariantScope scope = variant.variantData.scope
            AndroidBuilder androidBuilder = scope.getGlobalScope().androidBuilder
            DexOptions dexOptions = project.android.getDexOptions()
            DexingType dexingType = DexingType.MONO_DEX
            boolean preDexEnabled = false
            FileCollection mainDexListFile = null
            TargetInfo targetInfo = androidBuilder.getTargetInfo()
            DexByteCodeConverter dexByteCodeConverter = androidBuilder.getDexByteCodeConverter()
            MessageReceiver errorReporter
            String curVersionString = androidBuilder.createdBy.substring(createPrefix.length())
            VersionNumber currentVersion = VersionNumber.parse(curVersionString)
            VersionNumber miniVersion = VersionNumber.parse("3.1.0")
            println(" Android gradle " + curVersionString)
            if (currentVersion >= miniVersion) {
                errorReporter = androidBuilder.getMessageReceiver()
            } else {
                errorReporter = androidBuilder.getErrorReporter()
            }
            int minSdkVersion = scope.getMinSdkVersion().getFeatureLevel()
            if (!dexOptions.getKeepRuntimeAnnotatedClasses() && mainDexListFile == null) {
                logger.info("DexOptions.keepRuntimeAnnotatedClasses has no affect in native multidex.");
            }
            ProcessOutputHandler outputHandler =
                    new ParsingProcessOutputHandler(
                            new ToolOutputParser(new DexParser(), Message.Kind.ERROR, logger),
                            new ToolOutputParser(new DexParser(), logger),
                            errorReporter);
            ProcessOutput output
            Closeable ignored = output = outputHandler.createOutput()
            DxContext dxContext =
                    new DxContext(
                            output.getStandardOutput(), output.getErrorOutput())
            dexInfos.each {
                if (it.needUpdate) {
                    Collection<File> transformInputs = project.files(it.classesToUpdateDir).collect()
                    try {
                        File outputDir = dexesInfoDir
                        File mainDexList = null

                        dexByteCodeConverter.convertByteCode(
                                transformInputs,
                                outputDir,
                                dexingType.isMultiDex(),
                                mainDexList,
                                dexOptions,
                                outputHandler,
                                minSdkVersion);
                        // rename
                        File srcDexFile = new File(dexesInfoDir, "classes.dex")
                        //srcDexFile.renameTo(it.srcDex)

                        // merge dex
                        Dex[] dexesToMerge = [new Dex(srcDexFile), new Dex(it.dstDex)]
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
        }
    }
}
