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
import com.android.build.gradle.internal.tasks.DexMergingTask
import com.android.build.gradle.tasks.ProcessAndroidResources
import com.android.build.gradle.internal.pipeline.TransformTask
import org.gradle.BuildListener
import org.gradle.BuildResult
import org.gradle.api.Project
import org.gradle.api.Plugin
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.TaskState
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.Task
import org.gradle.api.file.FileTree

import com.google.gson.GsonBuilder
import com.google.gson.Gson
import com.android.build.gradle.internal.cxx.json.PlainFileGsonTypeAdaptor
import com.android.build.gradle.internal.dsl.SigningConfig
import org.gradle.util.VersionNumber

class HostPlugin implements Plugin<Project> {
    private Project project
    private File unzipHostApk

    void apply(Project project) {
        this.project = project
        printTaskRuntime(project)
        project.extensions.create('fastDebug', HostExtension.class)
        String curVersionString = Utils.androidGradleVersion()
        if (curVersionString == null) {
            return
        }
        VersionNumber currentVersion = VersionNumber.parse(curVersionString)
        VersionNumber miniVersion = VersionNumber.parse("3.3.0")
        def variants = project.android.applicationVariants
        variants.whenObjectAdded { ApplicationVariantImpl variant ->
            if (variant.buildType.name == "debug") {
                unzipHostApk = new File(project.buildDir, "hostApk")
                File dummyApk = variant.outputs[0].outputFile
                Task packageApplication
                Task mergeResources
                Task preBuild
                if (currentVersion >= miniVersion ) {
                    packageApplication = variant.packageApplicationProvider.get()
                    mergeResources = variant.mergeResourcesProvider.get()
                    preBuild = variant.preBuildProvider.get()
                }else {
                    packageApplication = variant.packageApplication
                    mergeResources = variant.mergeResources
                    preBuild = variant.preBuild
                }
                Task mergeJniLibsTask = project.tasks.withType(TransformTask.class).find {
                    it.transform.name == 'mergeJniLibs' && it.variantName == variant.name
                }

                Task processAndroidResourcesTask = project.tasks.withType(ProcessAndroidResources.class).find {
                    it.variantName == variant.name
                }

                Task stripDebugSymbolTask = project.tasks.withType(TransformTask.class).find {
                    it.transform.name == 'stripDebugSymbol' && it.variantName == variant.name
                }

                Task dexBuilderTask = project.tasks.withType(TransformTask.class).find {
                    it.transform.name == 'dexBuilder' && it.variantName == variant.name
                }

                Task dexMergerTask = project.tasks.withType(TransformTask.class).find {
                    it.transform.name == 'dexMerger' && it.variantName == variant.name
                }

                if (dexMergerTask == null) {
                    dexMergerTask = project.tasks.withType(DexMergingTask.class).find {
                        it.variantName == variant.name
                    }
                }

                stripDebugSymbolTask.enabled = false
                processAndroidResourcesTask.enabled = false
                packageApplication.enabled = false
                mergeResources.enabled = false
                dexBuilderTask.enabled = false
                if (dexMergerTask != null) {
                    dexMergerTask.enabled = false
                }

                HostExtension fastDebug = project.fastDebug
                if (fastDebug.hostApk == null || !(new File(fastDebug.hostApk).exists())) {
                    return
                }
                preBuild.doFirst {
                    if (!unzipHostApk.exists()) {
                        project.copy {
                            from project.zipTree(fastDebug.hostApk)
                            into unzipHostApk
                            exclude "**/META-INF/**"
                            includeEmptyDirs = false
                        }
                    }
                }
                Task reassembleHostTask = project.task("reAssembleHost", type: Jar) {
                    from unzipHostApk
                    archiveName "${unzipHostApk.name}_unsign.apk"
                    destinationDir dummyApk.parentFile
                }

                CustomDexTask customDexTask = project.task("customDex${variant.name.capitalize()}", type:CustomDexTask.class, { CustomDexTask dexTask->
                    dexTask.classesDirs = []
                    dexTask.outputDir = new File(project.buildDir, "intermediates/hostDexInfo/tmp")
                    if (!dexTask.outputDir.exists())dexTask.outputDir.mkdirs()
                    DependencyUtils.collectDependencyProjectClassesDirs(project, variant.name, dexTask.classesDirs)
                    dexTask.configure(project, variant)
                })
                if (fastDebug.updateJavaClass) {
                    reassembleHostTask.dependsOn customDexTask
                }

                packageApplication.finalizedBy reassembleHostTask
                mergeJniLibsTask.doLast {
                    FileTree dummyHostNativeLibs = project.fileTree("${project.buildDir}/intermediates/transforms/mergeJniLibs").include("**/*.so")
                    File hostNativeLibsDir = new File(unzipHostApk, "lib")
                    dummyHostNativeLibs.each { so ->
                        File hostNativeLibsABI = new File(hostNativeLibsDir, so.parentFile.name)
                        if (hostNativeLibsABI.exists()) {
                            File hostNativeLib = new File(hostNativeLibsABI, so.name)
                            if (hostNativeLib.exists()) {
                                println(" update so: " + so + " >> " + hostNativeLibsABI)
                                project.copy {
                                    from so
                                    into hostNativeLibsABI
                                }
                            }
                        }
                    }
                }
                reassembleHostTask.doLast {
                    File srcPath = new File(dummyApk.parentFile, "${unzipHostApk.name}_unsign.apk")
                    File dstPath = new File(dummyApk.parentFile, "${unzipHostApk.name}_sign.apk")
                    def signingConfig
                    if (currentVersion >= miniVersion) {
                        signingConfig = getSig(packageApplication.signingConfig.asFileTree.singleFile)
                    }else {
                        signingConfig = packageApplication.signingConfig
                    }
                    String keyStore = signingConfig.storeFile.path
                    String storePass = signingConfig.storePassword
                    String alias = signingConfig.keyAlias
                    println "Sign apk, storeFile: " + keyStore + " storePass:" + storePass + " alias:" + alias + " source path: " + srcPath + ", destination path: " + dstPath
                    project.exec {
                        executable "jarsigner"
                        args "-keystore", keyStore, "-storepass", storePass, "-signedjar", dstPath, srcPath, alias
                    }
                }

                Task renameHostTask = project.task("updateDummyHostApk") {
                    doLast {
                        File srcPath = new File(dummyApk.parentFile, "${unzipHostApk.name}_sign.apk")
                        File dstPath = dummyApk
                        project.copy {
                            from dummyApk
                            into dstPath.parentFile
                            rename dummyApk.name, "dummyHost.apk"
                        }
                        project.copy {
                            from srcPath
                            into dstPath.parentFile
                            rename srcPath.name, dstPath.name
                        }
                    }
                }

                reassembleHostTask.finalizedBy renameHostTask
            }
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

            //task.project.logger.warn "${task.path} spend ${ms}ms"
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

        @Override
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

    SigningConfig getSig(File file) {
        GsonBuilder gsonBuilder = new GsonBuilder()
        gsonBuilder.registerTypeAdapter(File.class, new PlainFileGsonTypeAdaptor())
        Gson gson = gsonBuilder.create()
        file.withReader {
            return gson.fromJson(it, SigningConfig.class)
        }
    }
}
