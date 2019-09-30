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
import com.android.build.gradle.internal.pipeline.TransformTask
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.transforms.DexArchiveBuilderTransform
import com.android.build.gradle.internal.transforms.DexArchiveBuilderTransformBuilder
import com.android.ide.common.blame.MessageReceiver
import com.android.ide.common.process.ProcessOutputHandler
import org.gradle.api.Project
import org.gradle.util.VersionNumber
import com.android.build.api.transform.Context
import org.gradle.api.Task
import com.android.build.gradle.options.BooleanOption;
import com.android.build.gradle.options.IntegerOption;
import com.android.build.gradle.options.ProjectOptions
import com.android.build.gradle.options.SyncOptions;
import com.android.builder.core.DexOptions
import java.util.function.Supplier

class GradleApiAdapter {

    static MessageReceiver getErrorReporter(ApplicationVariantImpl variant) {
        MessageReceiver errorReporter
        if (isGradleVersionGreaterOrEqualTo("3.1.0")) {
            errorReporter  = variant.variantData.scope.globalScope.messageReceiver
        } else {
            VariantScope scope = variant.variantData.scope
            def androidBuilder = scope.getGlobalScope().androidBuilder
            errorReporter = androidBuilder.getErrorReporter()
        }
        return errorReporter
    }

    static ClassToDex createClassToDex(Context context, Project project, ApplicationVariantImpl variant, ProcessOutputHandler outputHandler) {
        ClassToDex classToDex
        if (isGradleVersionGreaterOrEqualTo("3.3.0")) {
            classToDex = new ClassToDex330(context, project, variant)
        } else {
            classToDex = new ClassToDex300(project, variant, outputHandler)
        }
        return classToDex
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
            String taskName = variant.variantData.scope.getTaskName("merge", "JniLibFolders")
            Task mergeJniLibsTask = project.tasks.findByName(taskName)
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

    static DexArchiveBuilderTransform createDexArchiveBuilderTransform(Context context, Project prj, ApplicationVariantImpl variant) {
        DexOptions dexOptions = prj.android.getDexOptions()
        VariantScope scope = variant.variantData.scope
        int minSdkVersion = scope
                .getVariantConfiguration()
                .getMinSdkVersionWithTargetDeviceApi()
                .getFeatureLevel()
        ProjectOptions projectOptions = new ProjectOptions(prj)

        DexArchiveBuilderTransformBuilder dexArchiveBuilderTransformBuilder = new DexArchiveBuilderTransformBuilder()
                .setDexOptions((DexOptions) dexOptions)
                .setMessageReceiver(scope.getGlobalScope().getMessageReceiver())
                .setUserLevelCache(scope.getGlobalScope().getBuildCache())
                .setMinSdkVersion(minSdkVersion)
                .setDexer(scope.getDexer())
                .setUseGradleWorkers(projectOptions.get(BooleanOption.ENABLE_GRADLE_WORKERS))
                .setInBufferSize(projectOptions.get(IntegerOption.DEXING_READ_BUFFER_SIZE))
                .setOutBufferSize(projectOptions.get(IntegerOption.DEXING_WRITE_BUFFER_SIZE))
                .setIsDebuggable(true)
                .setJava8LangSupportType(scope.getJava8LangSupportType())
                .setProjectVariant(getProjectVariantId(scope))
                .setNumberOfBuckets(projectOptions.get(IntegerOption.DEXING_NUMBER_OF_BUCKETS))
                .setIncludeFeaturesInScope(scope.consumesFeatureJars())
                .setEnableDexingArtifactTransform(true)

        if (isGradleVersionGreaterOrEqualTo("3.5.0")) {
            dexArchiveBuilderTransformBuilder
                    .setAndroidJarClasspath(scope.getGlobalScope().getFilteredBootClasspath())
                    .setErrorFormatMode(SyncOptions.getErrorFormatMode(scope.getGlobalScope().getProjectOptions()))
        }else {
            def androidBuilder = scope.getGlobalScope().androidBuilder
            dexArchiveBuilderTransformBuilder
                    .setIsInstantRun(false)
                    .setAndroidJarClasspath(new Supplier<List<File>>() {
                        @Override
                        List<File> get() {
                            androidBuilder.getBootClasspath(false)
                    }
            })
        }
        return dexArchiveBuilderTransformBuilder.createDexArchiveBuilderTransform()
    }

    static boolean isGradleVersionGreaterOrEqualTo(String targetVersionString) {
        String curVersionString = Utils.androidGradleVersion()
        VersionNumber currentVersion = VersionNumber.parse(curVersionString)
        VersionNumber targetVersion = VersionNumber.parse(targetVersionString)
        return currentVersion >= targetVersion
    }
}
