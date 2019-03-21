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
import org.gradle.api.Task
import org.gradle.util.VersionNumber
import com.google.devrel.gmscore.tools.apk.arsc.*

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
                int minSdkVersion = variant.variantData.scope.getMinSdkVersion().getFeatureLevel()
                if (currentVersion >= miniVersion) {
                    packageApplication = variant.packageApplicationProvider.get()
                    mergeResources = variant.mergeResourcesProvider.get()
                } else {
                    packageApplication = variant.packageApplication
                    mergeResources = variant.mergeResources
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
                File hostFilesToUpdateDir = new File(project.buildDir, "intermediates/hostFilesToUpdate")
                File hostDexToUpdateDir = new File(hostFilesToUpdateDir, "dex")

                CustomDexTask customDexTask = project.task("customDex${variant.name.capitalize()}", type: CustomDexTask.class, { CustomDexTask dexTask ->
                    dexTask.classesDirs = []
                    dexTask.outputDir = hostDexToUpdateDir
                    if (!dexTask.outputDir.exists()) dexTask.outputDir.mkdirs()
                    DependencyUtils.collectDependencyProjectClassesDirs(project, variant.name, dexTask.classesDirs)
                    dexTask.configure(project, variant, fastDebug.updateJavaClass)
                })
                ApkUpdateTask apkUpdateTask = project.task("apkUpdate${variant.name.capitalize()}", type: ApkUpdateTask.class, { ApkUpdateTask updateTask ->
                    updateTask.inputDirs = []
                    updateTask.outputDir = new File(project.buildDir, "intermediates/hostFilesToUpdate/output")
                    if (!updateTask.outputDir.exists()) updateTask.outputDir.mkdirs()
                    updateTask.inputDirs.add(hostDexToUpdateDir)
                    updateTask.inputDirs.add(mergeJniLibsTask.streamOutputFolder)
                    updateTask.configure(project, dummyApk, variant.signingConfig, minSdkVersion)
                })

                customDexTask.doFirst {
                    if (hostDexToUpdateDir.listFiles().length == 0) {
                        if (fastDebug.updateJavaClass) {
                            project.copy {
                                from project.zipTree(fastDebug.hostApk)
                                into hostDexToUpdateDir
                                include "*.dex"
                                include "AndroidManifest.xml"
                            }
                        } else {
                            project.copy {
                                from project.zipTree(fastDebug.hostApk)
                                into hostDexToUpdateDir
                                include "AndroidManifest.xml"
                            }
                        }
                        modifyAndroidManifestToDebuggable(new File(hostDexToUpdateDir, "AndroidManifest.xml"))
                    }
                }

                apkUpdateTask.dependsOn customDexTask
                apkUpdateTask.doFirst {
                    if (!dummyApk.exists()) {
                        File hostApkFile = new File(fastDebug.hostApk)
                        project.copy {
                            from hostApkFile
                            into dummyApk.parentFile
                            rename hostApkFile.name, dummyApk.name
                        }
                    }
                }

                packageApplication.finalizedBy apkUpdateTask
            }
        }
    }

    void modifyAndroidManifestToDebuggable(File manifestFile) {
        FileInputStream inputStream = new FileInputStream(manifestFile);
        BinaryResourceFile binaryResourceFile = BinaryResourceFile.fromInputStream(inputStream);
        inputStream.close();
        XmlStartElementChunk applicationChunk = null;
        StringPoolChunk stringPoolChunk = null;
        XmlResourceMapChunk xmlResourceMapChunk = null;
        for (Chunk chunk : binaryResourceFile.getChunks()) {
            if (chunk instanceof XmlChunk) {
                for (Chunk subChunk : ((XmlChunk) chunk).getChunks().values()) {
                    if (subChunk instanceof XmlStartElementChunk) {
                        XmlStartElementChunk xmlStartElementChunk = (XmlStartElementChunk) subChunk;
                        if (xmlStartElementChunk.getName().equals("application")) {
                            applicationChunk = xmlStartElementChunk;
                        }
                    } else if (subChunk instanceof StringPoolChunk) {
                        stringPoolChunk = (StringPoolChunk) subChunk;
                    } else if (subChunk instanceof XmlResourceMapChunk ) {
                        xmlResourceMapChunk = (XmlResourceMapChunk)subChunk;
                    }
                    if (stringPoolChunk != null && applicationChunk != null && xmlResourceMapChunk != null) {
                        break;
                    }
                }
            }
        }

        XmlAttribute debugAttribute = null;
        if (applicationChunk != null) {
            for (XmlAttribute attribute : applicationChunk.getAttributes()) {
                if (attribute.name().equals("debuggable")) {
                    debugAttribute = attribute;
                }
            }
        }
        boolean needModify = false;
        if (debugAttribute != null) {
            if (debugAttribute.rawValueIndex() != -1) {
                debugAttribute.setRawValueIndex(-1);
                needModify = true;
            }
            if (debugAttribute.typedValue().data() != -1) {
                debugAttribute.typedValue().setDataValue(-1);
                needModify = true;
            }
        } else {
            int namespaceIndex = stringPoolChunk.indexOf("http://schemas.android.com/apk/res/android");
            int nameIndex = stringPoolChunk.indexOf("debuggable");
            int rawValueIndex = -1;
            int debugResid = 0x0101000f;
            if (nameIndex == -1) {
                nameIndex = addAddAttribute(stringPoolChunk, xmlResourceMapChunk, "debuggable", debugResid);
            }
            if (namespaceIndex == -1) {
                namespaceIndex = stringPoolChunk.addString("http://schemas.android.com/apk/res/android");
            }
            BinaryResourceValue typedValue = new BinaryResourceValue(8, BinaryResourceValue.Type.INT_BOOLEAN, -1);
            debugAttribute = new XmlAttribute(namespaceIndex, nameIndex, rawValueIndex, typedValue, applicationChunk);
            int i = 0;
            List<XmlAttribute> xmlAttributes = applicationChunk.getAttributes();
            for (; i < xmlAttributes.size(); i++) {
                int resId = xmlResourceMapChunk.getResources().get(xmlAttributes.get(i).nameIndex());
                if (resId > debugResid) {
                    break;
                }
            }
            applicationChunk.addAttribute(i, debugAttribute);
            needModify = true;
        }

        println(" apk is debuggable " + (!needModify))
        if (needModify) {
            FileOutputStream newFile = new FileOutputStream(manifestFile);
            newFile.write(binaryResourceFile.toByteArray());
            newFile.flush();
            newFile.close();
        }
    }

     int addAddAttribute(StringPoolChunk stringPoolChunk, XmlResourceMapChunk resourceMapChunk, String attributeName, int attributeId) {
        int idx = stringPoolChunk.addString(attributeName);
        List<Integer> resourceIds = resourceMapChunk.getResources();
        while (resourceIds.size() < idx) {
            resourceIds.add(0);
        }
        resourceIds.add(attributeId);
        return idx;
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
}
