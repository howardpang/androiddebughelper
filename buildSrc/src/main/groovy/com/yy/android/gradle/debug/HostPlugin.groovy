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
import com.android.build.gradle.tasks.ProcessAndroidResources
import org.gradle.BuildListener
import org.gradle.BuildResult
import org.gradle.api.Project
import org.gradle.api.Plugin
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.TaskState
import org.gradle.api.Task

class HostPlugin implements Plugin<Project> {
    private Project project
    private File unzipHostApk

    void apply(Project project) {
        this.project = project
        printTaskRuntime(project)
        project.extensions.create('debughelp', HostExtension.class)
        String curVersionString = Utils.androidGradleVersion()
        if (curVersionString == null) {
            return
        }
        CustomTransform customTransform = new CustomTransform()
        project.android.registerTransform(customTransform)

        def variants = project.android.applicationVariants
        variants.whenObjectAdded { ApplicationVariantImpl variant ->
            if (variant.buildType.name == "debug") {
                unzipHostApk = new File(project.buildDir, "hostApk")
                File dummyApk = variant.outputs[0].outputFile
                Task packageApplication = GradleApiAdapter.getPackageApplicationTask(variant)
                Task mergeResources = GradleApiAdapter.getMergeResourcesTask(variant)
                Task javaCompileTask = GradleApiAdapter.getJavaCompileTask(variant)
                int minSdkVersion = variant.getMergedFlavor().minSdkVersion.getApiLevel()
                Task processAndroidResourcesTask = project.tasks.withType(ProcessAndroidResources.class).find {
                    it.variantName == variant.name
                }
                Task stripDebugSymbolTask = GradleApiAdapter.getStripDebugSymbolTask(project, variant)
                Map<Task, File> dexBuilderTaskInfo = GradleApiAdapter.getDexBuilderTaskInfo(project, variant)
                Task dexBuilderTask = dexBuilderTaskInfo.entrySet()[0].key
                File dexBuilderOutputDir = dexBuilderTaskInfo.entrySet()[0].value

                Set<Task> dexMergerTasks = GradleApiAdapter.getDexMergerTasks(project, variant)
                if (stripDebugSymbolTask != null) {
                    stripDebugSymbolTask.enabled = false
                }
                processAndroidResourcesTask.enabled = false
                packageApplication.enabled = false
                mergeResources.enabled = false
                if (dexMergerTasks != null) {
                    dexMergerTasks.each {
                        it.enabled = false
                    }
                }
                HostExtension hostExtension = project.debughelp
                if (hostExtension.hostApk == null || !(new File(hostExtension.hostApk).exists())) {
                    return
                }

                List<File> jniFolders = GradleApiAdapter.getJniFolders(project, variant)
                File dexUpdateTaskOutputDir = new File(project.buildDir, "debughelp/hostFilesToUpdate")
                File apkUpdateTaskOutputDir = new File(project.buildDir, "debughelp/output")
                File collectSubPrjClassTaskOutputDir = new File(project.buildDir, "debughelp/collectClassTask/${variant.name}")

                CollectSubPrjClassTask collectSubPrjClassTask = project.task("collectSubPrjClass${variant.name.capitalize()}", type: CollectSubPrjClassTask.class, { CollectSubPrjClassTask task ->
                    task.outputDir = collectSubPrjClassTaskOutputDir
                    if (!task.outputDir.exists()) task.outputDir.mkdirs()
                    task.configure(variant, hostExtension, customTransform)
                })
                //Delay to configure collectSubPrjClassTask classDirs, because some sub project will resolve later than host project
                variant.preBuild.doFirst {
                    List<File> classDirs = []
                    DependencyUtils.collectDependencyProjectClassesDirs(project, variant.name, classDirs)
                    collectSubPrjClassTask.classesDirs = project.files(classDirs)
                }

                DexUpdateTask dexUpdateTask = project.task("dexUpdate${variant.name.capitalize()}", type: DexUpdateTask.class, { DexUpdateTask dexTask ->
                    dexTask.dexDirToUpdate = dexBuilderOutputDir
                    dexTask.outputDir = dexUpdateTaskOutputDir
                    if (!dexTask.outputDir.exists()) dexTask.outputDir.mkdirs()
                    dexTask.configure(project, variant, hostExtension, minSdkVersion)
                })
                ApkUpdateTask apkUpdateTask = project.task("apkUpdate${variant.name.capitalize()}", type: ApkUpdateTask.class, { ApkUpdateTask updateTask ->
                    updateTask.inputDirs = []
                    updateTask.outputDir = apkUpdateTaskOutputDir
                    if (!updateTask.outputDir.exists()) updateTask.outputDir.mkdirs()
                    updateTask.inputDirs.add(dexUpdateTaskOutputDir)
                    updateTask.inputDirs.addAll(jniFolders)
                    updateTask.configure(project, dummyApk, variant.signingConfig, minSdkVersion, hostExtension, variant.applicationId)
                })

                Task customTransformTask = project.tasks.withType(TransformTask.class).find {
                    it.transform.class == CustomTransform.class && it.variantName == variant.name
                }

                extractFilesFromHostApk(hostExtension, dexUpdateTaskOutputDir)

                if (!hostExtension.updateJavaClass) {
                    collectSubPrjClassTask.enabled = false
                    dexBuilderTask.enabled = false
                    dexUpdateTask.enabled = false
                }
                collectSubPrjClassTask.dependsOn javaCompileTask
                customTransformTask.dependsOn collectSubPrjClassTask
                dexUpdateTask.dependsOn dexBuilderTask
                apkUpdateTask.dependsOn dexUpdateTask
                packageApplication.finalizedBy apkUpdateTask
            }
        }
    }

    void extractFilesFromHostApk(HostExtension hostExtension, File outputDir) {
        // extract dex and manifest from host apk
        File manifest = new File(outputDir, "AndroidManifest.xml")
        if (manifest.exists()) {
            return
        }
        if (hostExtension.updateJavaClass) {
            project.copy {
                from project.zipTree(hostExtension.hostApk)
                into outputDir
                include "*.dex"
                include "AndroidManifest.xml"
            }
        } else {
            project.copy {
                from project.zipTree(hostExtension.hostApk)
                into outputDir
                include "AndroidManifest.xml"
            }
        }

        if (hostExtension.modifyApkDebuggable) {
            ManifestEditor.modifyAndroidManifestToDebuggable(manifest)
        }
    }

    class BuildTimeListener implements TaskExecutionListener, BuildListener {
        private long beforeMS
        private times = []

        @Override
        void beforeExecute(Task task) {
            beforeMS = System.currentTimeMillis()
        }

        @Override
        void afterExecute(Task task, TaskState taskState) {
            def ms = System.currentTimeMillis() - beforeMS
            times.add([ms, task.path])
        }

        @Override
        void buildFinished(BuildResult result) {
            println "Task spend time:"
            int totalTime = 0;
            times.sort {
                it[0]
            }
            times = times.reverse()
            for (time in times) {
                if (time[0] >= 50) {
                    printf "%7sms  %s\n", time
                }
                totalTime += time[0]
            }

            int mins = 0;
            if (totalTime > 60000) {
                mins = totalTime / 60000
                totalTime = totalTime % 60000
            }
            float second = 0;
            if (totalTime > 1000) {
                second = totalTime
                second = totalTime / 1000
            }

            println "Build Task spend total time: ${mins} mins ${second} secs"
        }

        //@Override
        void buildStarted(Gradle gradle) {}

        @Override
        void projectsEvaluated(Gradle gradle) {}

        @Override
        void projectsLoaded(Gradle gradle) {}

        @Override
        void settingsEvaluated(Settings settings) {}
    }

    void printTaskRuntime(Project prj) {
        prj.gradle.addListener(new BuildTimeListener())
    }
}
