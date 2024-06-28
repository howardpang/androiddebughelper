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

import org.gradle.BuildResult
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.initialization.DefaultSettings
import org.gradle.process.ExecResult
import org.gradle.process.internal.DefaultExecAction
import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.process.internal.ExecActionFactory

class DebugHelper implements Plugin<DefaultSettings> {
    protected DefaultSettings settings
    private static final String DEFAULT_MIN_SDK = "23"
    private static final String DEFAULT_TARGET_SDK = "24"
    private File mDummyHostDir
    private String mHostApk
    private HostInfo mHostInfo

    void apply(DefaultSettings settings) {
        this.settings = settings
        settings.gradle.settingsEvaluated {
            if (settings.hasProperty("hostApk")) {
                mHostApk = settings.hostApk
            }
            if (mHostApk != null) {
                if (!new File(mHostApk).exists()) {
                    throw new RuntimeException(" host apk not exist: ${mHostApk}")
                }
            }
            String dummyHostName = "dummyHost"
            mDummyHostDir = new File(settings.rootDir, "${dummyHostName}")
            HostInfo hostInfo = getHostInfoFromSetting()
            if (mDummyHostDir.exists()) {
                if (hostInfo.mHostPackageName == null || hostInfo.mHostLaunchActivity == null) {
                    try {
                        File manifest = new File(mDummyHostDir, "src/main/AndroidManifest.xml")
                        def manifestParser = new XmlParser(false, false).parse(manifest)
                        hostInfo.mHostPackageName = manifestParser.@package
                        hostInfo.mHostLaunchActivity = manifestParser.application.activity[0].@'android:name'
                    }
                    catch (Exception e) {
                        println("getHostInfo " + e.toString())
                    }
                }
            } else {
                HostInfo apkHostInfo = getHostInfoFromApk()
                hostInfo.update(apkHostInfo)
            }
            mHostInfo = hostInfo
            if (mHostInfo.mHostPackageName == null) {
                return
            }
            println("host package name: " + mHostInfo.mHostPackageName)
            println("host apk: " + mHostApk)
            println("host flavor: " + mHostInfo.mHostFlavor)
            println("host launch activity: " + mHostInfo.mHostLaunchActivity)
            println("update java class: " + mHostInfo.mUpdateJavaClass)
            println("modify apk debuggable: " + mHostInfo.mModifyApkDebuggable)
            if (!mDummyHostDir.exists()) {
                mDummyHostDir.mkdirs()
                createDummyHost(settings, mDummyHostDir, mHostInfo)
            }

            settings.include(":${dummyHostName}")
            settings.project(":${dummyHostName}").projectDir = mDummyHostDir
            boolean hookExternalBuild = true
            if (settings.hasProperty("hookExternalBuild")) {
                hookExternalBuild = settings.hookExternalBuild.toBoolean()
            }
            println("hookExternalBuild: " + hookExternalBuild)
            if (hookExternalBuild) {
                settings.gradle.beforeProject { Project p ->
                    if (p.name == dummyHostName && mHostApk != null) {
                        hookDummyHost(p)
                    } else if (p.name == settings.rootProject.name && p.projectDir == settings.rootDir) {
                        /**
                         * Note, there are two classloader context when hook project, for example 'com.android.tools.build:gradle:3.0.1' dependency
                         * will be loaded in setting classloader and rootProject classloader, the class with same name is different in these two classloader,
                         * so there are some job  can't hook in here, we should create other plugin to do
                         */
                        p.buildscript.dependencies.add("classpath", "${BuildConfig.GROUP}:${BuildConfig.NAME}:${BuildConfig.VERSION}")
                        p.afterEvaluate {
                            Task cleanTask
                            if (p.tasks.hasProperty("clean")) {
                                cleanTask = p.tasks.getByName("clean")
                            } else {
                                cleanTask = p.tasks.create("clean")
                            }
                            cleanTask.doFirst {
                                updateDummyHost()
                            }
                        }
                    } else {
                        hookDependenciesProject(p)
                    }
                }
                settings.gradle.buildFinished { BuildResult result->
                    if (result.action == "Configure" && result.failure != null && result.failure.getMessage().indexOf("project ':${dummyHostName}'")) {
                        //Try to update dummy host because user may update host info
                        updateDummyHost()
                    }
                }
            }
        }
    }

    private void updateDummyHost() {
        println("update dummyHost project")
        HostInfo newHostInfo = getHostInfoFromSetting()
        HostInfo apkHostInfo = getHostInfoFromApk()
        newHostInfo.update(apkHostInfo)
        mHostInfo = newHostInfo
        createDummyHost(settings, mDummyHostDir, mHostInfo)
    }

    private void hookDummyHost(Project p) {
        p.afterEvaluate {
            if (mHostInfo.mExcludeSo != null) {
                String[] splits = mHostInfo.mExcludeSo.split(";")
                p.debughelp.excludeSo = splits
            }
            p.debughelp.hostApk = mHostApk
            p.debughelp.updateJavaClass = (mHostInfo.mUpdateJavaClass && mHostApk != null)
            p.debughelp.modifyApkDebuggable = (mHostInfo.mModifyApkDebuggable && mHostApk != null)
            if (mHostInfo.mExtraFilesToUpdate != null) {
                p.debughelp.extraFilesToUpdate = mHostInfo.mExtraFilesToUpdate
            }
        }
    }

    private void hookDependenciesProject(Project project) {
        project.afterEvaluate {
            if (project.hasProperty("android") && project.android.class != null && project.android.class.name.find("com.android.build.gradle.LibraryExtension") != null) {
                project.android.packagingOptions {
                    doNotStrip "*/armeabi/*.so"
                    doNotStrip "*/armeabi-v7a/*.so"
                    doNotStrip "*/x86/*.so"
                }
                if (project.android.productFlavors.size() > 0) {
                    project.android.productFlavors.each { f ->
                        Task debugTask
                        Task releaseTask
                        project.tasks.whenObjectAdded {
                            if (it.name == "externalNativeBuild${f.name.capitalize()}Debug") {
                                debugTask = it
                                if (releaseTask != null) {
                                    hookExternalNativeBuildTask(project, debugTask, releaseTask)
                                }
                            }
                            if (it.name == "externalNativeBuild${f.name.capitalize()}Release") {
                                releaseTask = it
                                if (debugTask != null) {
                                    hookExternalNativeBuildTask(project, debugTask, releaseTask)
                                }
                            }
                        }
                    }
                } else {
                    Task debugTask
                    Task releaseTask
                    project.tasks.whenObjectAdded {
                        if (it.name == "externalNativeBuildDebug") {
                            debugTask = it
                            if (releaseTask != null) {
                                hookExternalNativeBuildTask(project, debugTask, releaseTask)
                            }
                        }
                        if (it.name == "externalNativeBuildRelease") {
                            releaseTask = it
                            if (debugTask != null) {
                                hookExternalNativeBuildTask(project, debugTask, releaseTask)
                            }
                        }
                    }
                }
            }
        }
    }

    void hookExternalNativeBuildTask(Project project, Task debugTask, Task releaseTask) {
        if (debugTask != null && releaseTask != null) {
            println("hook tasks " + project.name + ":" + releaseTask.name + ", " + project.name + ":" + debugTask.name)
            releaseTask.dependsOn debugTask
            releaseTask.doLast {
                project.copy {
                    from debugTask.getSoFolder().parentFile
                    into releaseTask.getSoFolder().parentFile
                }
            }
        }
    }

    void createDummyHost(DefaultSettings settings, File dummyHostDir, HostInfo hostInfo) {
        println("create dummyHost project")
        List dependencies
        if (mHostApk != null) {
            dependencies = []
            settings.projectDescriptorRegistry.allProjects.each { p ->
                p.buildFile.find {
                    if (it == "apply plugin: 'com.android.library'") {
                        dependencies.add(p.path)
                        return true
                    } else if (it == "apply plugin: 'com.android.application'") {
                        return true
                    }
                }
            }
        }

        // create build.gradle
        File buildGradle = new File(dummyHostDir, "build.gradle")
        def pw = new PrintWriter(buildGradle.newWriter(false))
        pw.print("""apply plugin: 'com.android.application'
apply plugin: 'com.ydq.android.gradle.debug.HostPlugin'
android { 
    compileSdkVersion ${hostInfo.mTargetSdk} 
    defaultConfig { 
        applicationId "${hostInfo.mHostPackageName}" 
        minSdkVersion ${hostInfo.mMinSdk} 
        targetSdkVersion ${hostInfo.mTargetSdk} 
        versionCode 1 
        versionName "1.0" 
    } 
""")
        if (hostInfo.mSupportJava8) {
            pw.println("""
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
""")
        }
        if (hostInfo.mHostFlavor != null) {
            pw.println("""
    productFlavors {
        flavorDimensions 'default'
        ${hostInfo.mHostFlavor} {
            dimension 'default'
        }
    }
""")
        }
        pw.println("""
}

""")

        if (dependencies != null) {
            pw.println("dependencies {")
            dependencies.each {
                pw.println("    implementation project('${it}')")
            }
            pw.println("}")
        }

        pw.flush()
        pw.close()

        File srcMainDir = new File(dummyHostDir, "src/main")
        if (!srcMainDir.exists()) srcMainDir.mkdirs()

        // create AndroidManifest.xml
        File manifest = new File(srcMainDir, "AndroidManifest.xml")
        pw = new PrintWriter(manifest.newWriter(false))
        pw.print("""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="${hostInfo.mHostPackageName}">
""")
        if (hostInfo.mHostLaunchActivity != null) {
            pw.println("""
    <application>
        <activity android:name="${hostInfo.mHostLaunchActivity}" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
""")
        }
        pw.println("</manifest>")
        pw.flush()
        pw.close()

        if (hostInfo.mHostLaunchActivity != null) {
            int lastDotPos = hostInfo.mHostLaunchActivity.lastIndexOf(".")
            String launchActivityPackageName = hostInfo.mHostLaunchActivity.substring(0, lastDotPos)
            String launchActivityName = hostInfo.mHostLaunchActivity.substring(lastDotPos + 1)
            String launchActivityClassName = hostInfo.mHostLaunchActivity.replace(".", "/")
            File launchActivityClass = new File(srcMainDir, "java/${launchActivityClassName}.java")
            if (!launchActivityClass.parentFile.exists()) launchActivityClass.parentFile.mkdirs()

            pw = new PrintWriter(launchActivityClass.newWriter(false))
            pw.print("""
package ${launchActivityPackageName};

import android.app.Activity;
import android.os.Bundle;

public class ${launchActivityName} extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
""")
            pw.flush()
            pw.close()
        }

        // create Android.mk
        File amk = new File(dummyHostDir, "Android.mk")
        pw = new PrintWriter(amk.newWriter(false))
        pw.print("""
LOCAL_PATH := \$(call my-dir)
include \$(CLEAR_VARS)
LOCAL_SRC_FILES := dummy.cpp
LOCAL_MODULE := dummy
include \$(BUILD_SHARED_LIBRARY)

      """)
        pw.flush()
        pw.close()

        File dummy = new File(dummyHostDir, "dummy.cpp")
        dummy.createNewFile()
    }

    private HostInfo getHostInfoFromSetting() {
        HostInfo hostInfo = new HostInfo()
        if (settings.hasProperty("hostPackageName")) {
            hostInfo.mHostPackageName = settings.hostPackageName
        }
        if (settings.hasProperty("hostLaunchActivity")) {
            hostInfo.mHostLaunchActivity = settings.hostLaunchActivity
        }
        if (settings.hasProperty("hostFlavor")) {
            hostInfo.mHostFlavor = settings.hostFlavor
        }
        if (settings.hasProperty("minSdkVersion")) {
            hostInfo.mMinSdk = settings.minSdkVersion
        }
        if (settings.hasProperty("targetSdkVersion")) {
            hostInfo.mTargetSdk = settings.targetSdkVersion
        }
        if (settings.hasProperty("updateJavaClass")) {
            hostInfo.mUpdateJavaClass = settings.updateJavaClass
        }
        if (settings.hasProperty("modifyApkDebuggable")) {
            hostInfo.mModifyApkDebuggable = settings.modifyApkDebuggable
        }
        if (settings.hasProperty("excludeSo")) {
            hostInfo.mExcludeSo = settings.excludeSo
        }
        if (settings.hasProperty("supportJava8")) {
            hostInfo.mSupportJava8 = settings.supportJava8
        }
        if (settings.hasProperty("extraFilesToUpdate")) {
            hostInfo.mExtraFilesToUpdate = settings.extraFilesToUpdate
        }
        return hostInfo
    }

    private HostInfo getHostInfoFromApk() {
        HostInfo hostInfo = new HostInfo()
        if (mHostApk != null) {
            Properties properties = new Properties()
            properties.load(new File(settings.rootDir, 'local.properties').newDataInputStream())
            def sdkDir = properties.getProperty('sdk.dir')
            File aapt
            if (sdkDir != null) {
                File buildTools = new File(sdkDir, "build-tools")
                String aaptName = "aapt"
                if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                    aaptName = "aapt.exe"
                }
                if (buildTools.exists()) {
                    buildTools.listFiles().find {
                        aapt = new File(it, aaptName)
                        if (aapt.exists()) {
                            return true
                        }
                    }
                }
            }

            if (aapt != null && aapt.exists()) {
                DefaultExecAction execAction = settings.gradle.getServices().get(ExecActionFactory.class).newExecAction()
                execAction.setIgnoreExitValue(true)
                def stdout = new ByteArrayOutputStream()
                execAction.standardOutput = stdout
                execAction.errorOutput = stdout
                execAction.workingDir = settings.rootDir
                execAction.commandLine(aapt.path, "dump", "badging", mHostApk)
                ExecResult result = execAction.execute()
                if (result.exitValue == 0) {
                    List<String> apkInfo = stdout.toString().readLines()
                    String packageName
                    String launchActivity
                    String sdkVersion
                    String targetSdkVersion
                    String nativeCode
                    for (String line : apkInfo) {
                        if (packageName != null && sdkVersion != null && targetSdkVersion != null && launchActivity != null && nativeCode != null) {
                            break
                        }
                        if (packageName == null) {
                            String prefix = "package: name='"
                            if (line.startsWith(prefix)) {
                                packageName = line.substring(prefix.length(), line.indexOf("'", prefix.length()))
                            }
                        } else if (sdkVersion == null) {
                            String prefix = "sdkVersion:'"
                            if (line.startsWith(prefix)) {
                                sdkVersion = line.substring(prefix.length(), line.indexOf("'", prefix.length()))
                            }
                        } else if (targetSdkVersion == null) {
                            String prefix = "targetSdkVersion:'"
                            if (line.startsWith(prefix)) {
                                targetSdkVersion = line.substring(prefix.length(), line.indexOf("'", prefix.length()))
                            }
                        } else if (launchActivity == null) {
                            String prefix = "launchable-activity: name='"
                            if (line.startsWith(prefix)) {
                                launchActivity = line.substring(prefix.length(), line.indexOf("'", prefix.length()))
                            }
                        } else if (nativeCode == null) {
                            String prefix = "native-code: '"
                            if (line.startsWith(prefix)) {
                                nativeCode = line.substring(prefix.length(), line.indexOf("'", prefix.length()))
                            }
                        }
                    }
                    println("extract host info from apk : ${packageName} >> ${launchActivity} >> ${sdkVersion} >> ${targetSdkVersion} >> ${nativeCode}")

                    if (packageName != null) {
                        hostInfo.mHostPackageName = packageName
                    }
                    if (launchActivity != null) {
                        hostInfo.mHostLaunchActivity = launchActivity
                    }
                    if (sdkVersion != null) {
                        hostInfo.mMinSdk = sdkVersion
                    }
                    if (targetSdkVersion != null) {
                        hostInfo.mTargetSdk = targetSdkVersion
                    }
                } else {
                    println("run aapt cmd failure " + mHostApk)
                }
            } else {
                println("can't find aapt in " + sdkDir)
            }
        }
        return hostInfo
    }

    private class HostInfo {
        String mHostPackageName
        String mHostLaunchActivity
        String mHostFlavor
        String mMinSdk
        String mTargetSdk
        boolean mUpdateJavaClass = true
        boolean mModifyApkDebuggable = true
        String mExcludeSo
        boolean mSupportJava8 = true
        Map<File, String> mExtraFilesToUpdate

        void update(HostInfo hostInfo) {
            if (mHostPackageName == null && hostInfo.mHostPackageName != null) {
                mHostPackageName = hostInfo.mHostPackageName
            }
            if (mHostLaunchActivity == null && hostInfo.mHostLaunchActivity != null) {
                mHostLaunchActivity = hostInfo.mHostLaunchActivity
            }
            if (mHostFlavor == null && hostInfo.mHostFlavor != null) {
                mHostFlavor = hostInfo.mHostFlavor
            }
            if (mMinSdk == null && hostInfo.mMinSdk != null) {
                mMinSdk = hostInfo.mMinSdk
            }
            if (mMinSdk == null) {
                mMinSdk = DEFAULT_MIN_SDK
            }
            if (mTargetSdk == null && hostInfo.mTargetSdk != null) {
                mTargetSdk = hostInfo.mTargetSdk
            }
            if (mTargetSdk == null) {
                mTargetSdk = DEFAULT_TARGET_SDK
            }
            if (mExcludeSo == null && hostInfo.mExcludeSo != null) {
                mExcludeSo = hostInfo.mExcludeSo
            }
            if (mExtraFilesToUpdate == null && hostInfo.mExtraFilesToUpdate != null) {
                mExtraFilesToUpdate = hostInfo.mExtraFilesToUpdate
            }
        }
    }
}
