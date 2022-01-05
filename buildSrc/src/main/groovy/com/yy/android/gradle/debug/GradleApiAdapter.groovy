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

import com.android.build.gradle.internal.LoggerWrapper
import com.android.build.gradle.internal.api.ApplicationVariantImpl
import com.android.build.gradle.internal.pipeline.TransformTask
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.tasks.AndroidVariantTask
import com.android.build.gradle.internal.tasks.DexMergingTask
import com.android.ide.common.blame.Message
import com.android.ide.common.blame.MessageReceiver
import com.android.ide.common.blame.ParsingProcessOutputHandler
import com.android.ide.common.blame.parser.ToolOutputParser
import com.android.ide.common.process.ProcessOutputHandler
import org.gradle.api.Project
import org.gradle.util.VersionNumber
import org.gradle.api.Task

class GradleApiAdapter {

    static MessageReceiver getMessageReceiver(ApplicationVariantImpl variant, Project prj) {
        MessageReceiver errorReporter
        if (isGradleVersionGreaterOrEqualTo("4.1.0")) {
            //ProjectOptions projectOptions = new ProjectOptions(prj)
            errorReporter  = variant.variantData.globalScope.messageReceiver
        }
        else if (isGradleVersionGreaterOrEqualTo("3.1.0")) {
            errorReporter  = variant.variantData.scope.globalScope.messageReceiver
        } else {
            VariantScope scope = variant.variantData.scope
            def androidBuilder = scope.getGlobalScope().androidBuilder
            errorReporter = androidBuilder.getErrorReporter()
        }
        return errorReporter
    }

    static Task getStripDebugSymbolTask(Project project, ApplicationVariantImpl variant) {
        Task stripDebugSymbolTask
        if (isGradleVersionGreaterOrEqualTo("3.5.0")) {
            stripDebugSymbolTask = project.tasks.withType(Class.forName("com.android.build.gradle.internal.tasks.StripDebugSymbolsTask")).find {
                it.variantName == variant.name
            }
        }else {
            stripDebugSymbolTask = project.tasks.withType(TransformTask.class).find {
                it.transform.name == 'stripDebugSymbol' && it.variantName == variant.name
            }
        }
        return stripDebugSymbolTask
    }

    static List<File> getJniFolders(Project project, ApplicationVariantImpl variant) {
        List<File> jniFolders = []
        if (isGradleVersionGreaterOrEqualTo("3.5.0")) {
            Task mergeJniLibsTask = project.tasks.withType(Class.forName("com.android.build.gradle.tasks.MergeSourceSetFolders")).find {
                it.variantName == variant.name
            }
            if (mergeJniLibsTask != null) {
                jniFolders.add(mergeJniLibsTask.outputDir.getAsFile().get())
            }
            Task mergeNativeLibsTask = project.tasks.withType(Class.forName("com.android.build.gradle.internal.tasks.MergeNativeLibsTask")).find {
                it.variantName == variant.name
            }
            if (mergeNativeLibsTask != null) {
                jniFolders.add(mergeNativeLibsTask.outputDir.getAsFile().get())
            }
        }else {
            Task mergeJniLibsTask = project.tasks.withType(TransformTask.class).find {
                it.transform.name == 'mergeJniLibs' && it.variantName == variant.name
            }
            if (mergeJniLibsTask != null) {
                jniFolders.add(mergeJniLibsTask.streamOutputFolder)
            }
        }
        return jniFolders
    }

    static Task getPackageApplicationTask(ApplicationVariantImpl variant) {
        Task task
        if (isGradleVersionGreaterOrEqualTo("3.3.0")) {
            task = variant.packageApplicationProvider.get()
        }else {
            task = variant.packageApplication
        }
        return task
    }

    static Task getMergeResourcesTask(ApplicationVariantImpl variant) {
        Task task
        if (isGradleVersionGreaterOrEqualTo("3.3.0")) {
            task = variant.mergeResourcesProvider.get()
        }else {
            task = variant.mergeResources
        }
        return task
    }

    static Map getDexBuilderTaskInfo(Project project, ApplicationVariantImpl variant) {
        Map<Task, File> dexTaskInfo = [:]
        if (isGradleVersionGreaterOrEqualTo("3.6.0")) {
            Task task = project.tasks.withType(AndroidVariantTask.class).find {
                it.name == "dexBuilder${variant.name.capitalize()}" && it.variantName == variant.name
            }
            File outDir = task.mixedScopeOutputDex.asFile.get()
            dexTaskInfo.put(task, outDir)
        } else {
            Task task = project.tasks.withType(TransformTask.class).find {
                it.transform.name == 'dexBuilder' && it.variantName == variant.name
            }
            //The dex will always output to '0' sub dir
            File outDir = new File(task.getStreamOutputFolder(), "0")
            dexTaskInfo.put(task, outDir)
        }
        return dexTaskInfo
    }

    static Set<Task> getDexMergerTasks(Project project, ApplicationVariantImpl variant) {
        Set<Task> tasks
        if (isGradleVersionGreaterOrEqualTo("3.6.0")) {
            tasks = project.tasks.withType(AndroidVariantTask.class).findAll {
                it.name == "dexMerger${variant.name.capitalize()}" && it.variantName == variant.name
            }
        } else {
            tasks = project.tasks.withType(TransformTask.class).findAll {
                it.transform.name == 'dexMerger' && it.variantName == variant.name
            }
        }
        if (tasks.empty) {
            tasks = project.tasks.withType(DexMergingTask.class).findAll {
                it.variantName == variant.name
            }
        }
        return tasks
    }

    static boolean isGradleVersionGreaterOrEqualTo(String targetVersionString) {
        String curVersionString = Utils.androidGradleVersion()
        VersionNumber currentVersion = VersionNumber.parse(curVersionString)
        VersionNumber targetVersion = VersionNumber.parse(targetVersionString)
        return currentVersion >= targetVersion
    }

    private static String getProjectVariantId(VariantScope variantScope) {
        return variantScope.getGlobalScope().getProject().getName() + ":" + variantScope.getFullVariantName();
    }

    static Task getJavaCompileTask(ApplicationVariantImpl variant) {
        Task task
        if (isGradleVersionGreaterOrEqualTo("3.3.0")) {
            task = variant.javaCompileProvider.get()
        }else {
            task = variant.javaCompile
        }
        return task
    }

    static DexParser createDexParser(Project project, int minApiLevel) {
        DexParser dexParser
        if (isGradleVersionGreaterOrEqualTo("7.0.0")) {
            dexParser = new R8DexParser3073(project, minApiLevel)
        }else {
            dexParser = new DxDexParser()
        }
        return dexParser
    }

    static DexMerger createDexMerger(MessageReceiver errorReporter, File tmpDir, int minApiLevel) {
        DexMerger dexMerger
        if (isGradleVersionGreaterOrEqualTo("7.0.0")) {
            dexMerger = new R8DexMerger3073(tmpDir, minApiLevel)
        }else {
            LoggerWrapper loggerWrapper = LoggerWrapper.getLogger(DxDexParser.class)
            ProcessOutputHandler outputHandler =
                    new ParsingProcessOutputHandler(
                            new ToolOutputParser(new com.android.ide.common.blame.parser.DexParser(), Message.Kind.ERROR, loggerWrapper),
                            new ToolOutputParser(new com.android.ide.common.blame.parser.DexParser(), loggerWrapper),
                            errorReporter)
            dexMerger = new DxDexMerger(outputHandler)
        }
        return dexMerger
    }

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
