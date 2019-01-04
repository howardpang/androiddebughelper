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

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.bundling.Jar
import org.gradle.initialization.DefaultSettings
import org.gradle.process.ExecResult
import org.gradle.process.internal.DefaultExecAction
import org.gradle.internal.file.PathToFileResolver
import org.gradle.internal.Factory

class DebugHelper implements Plugin<DefaultSettings> {

    protected DefaultSettings settings
    private File mDummyHostDir

    private String mHostPackageName
    private String mHostLaunchActivity
    private String mHostApk
    private String mHostFlavor

    void apply(DefaultSettings settings) {
        this.settings = settings
        settings.gradle.settingsEvaluated {
            if (settings.hasProperty("hostPackageName")) {
                mHostPackageName = settings.hostPackageName
            }
            if (settings.hasProperty("hostLaunchActivity")) {
                mHostLaunchActivity = settings.hostLaunchActivity
            }
            if (settings.hasProperty("hostApk")) {
                mHostApk = settings.hostApk
            }
            if (settings.hasProperty("hostFlavor")) {
                mHostFlavor = settings.hostFlavor
            }

            String dummyHostName = "dummyHost"
            mDummyHostDir = new File(settings.rootDir, ".idea/${dummyHostName}")
            getHostInfo()
            if (mHostPackageName == null) {
                return
            }

            println("host package name: " + mHostPackageName)
            println("host apk: " + mHostApk)
            println("host flavor: " + mHostFlavor)
            println("host launch activity: " + mHostLaunchActivity)

            if (!mDummyHostDir.exists()) {
                List hostDependencies
                if (mHostApk != null) {
                    hostDependencies = []
                    settings.rootProject.children.each { p ->
                        p.buildFile.find {
                            if (it == "apply plugin: 'com.android.library'") {
                                hostDependencies.add(p.name)
                                return true
                            } else if (it == "apply plugin: 'com.android.application'") {
                                return true
                            }
                        }
                    }
                }

                createDummyHost(mDummyHostDir, mHostPackageName, mHostFlavor, hostDependencies, mHostLaunchActivity)
            }
            settings.include(":${dummyHostName}")
            settings.project(":${dummyHostName}").projectDir = mDummyHostDir
            boolean hookExternalBuild = true
            if (settings.hasProperty("hookExternalBuild")) {
                hookExternalBuild = settings.hookExternalBuild.toBoolean()
            }
            println("hookExternalBuild: " + hookExternalBuild)
            if (hookExternalBuild) {
                settings.gradle.beforeProject { p ->
                    if (p.name == dummyHostName && mHostApk != null) {
                        hookDummyHost(p)
                    } else if (p.name == settings.rootProject.name) {
                        p.task("cleanDummyHost") {
                            group = "clean"
                            doLast {
                                File ideaDir = new File(settings.rootDir, ".idea")
                                ideaDir.deleteDir()
                            }
                        }

                    } else {
                        hookDependenciesProject(p)
                    }
                }
            }
        }
    }

    private void hookDummyHost(Project p) {
        p.afterEvaluate {
            def variants = p.android.applicationVariants
            variants.whenObjectAdded { variant ->
                if (variant.buildType.name == "debug") {
                    File unzipHostApk = new File(p.buildDir, "hostApk")
                    File dummyApk = variant.outputs[0].outputFile
                    Task packageApplication = variant.packageApplication
                    Task reassembleHostTask = p.task("reAssembleHost", type: Jar) {
                        from unzipHostApk
                        archiveName "${unzipHostApk.name}_unsign.apk"
                        destinationDir dummyApk.parentFile
                    }
                    packageApplication.finalizedBy reassembleHostTask
                    reassembleHostTask.doFirst {
                        if (!unzipHostApk.exists()) {
                            p.copy {
                                from project.zipTree(mHostApk)
                                into unzipHostApk
                                exclude "**/META-INF/**"
                                includeEmptyDirs = false
                            }
                        }
                        FileTree dummyHostNativeLibs = p.fileTree("${p.buildDir}/intermediates/transforms/mergeJniLibs").include("**/*.so")
                        File hostNativeLibsDir = new File(unzipHostApk, "lib")
                        dummyHostNativeLibs.each { so ->
                            File hostNativeLibsABI = new File(hostNativeLibsDir, so.parentFile.name)
                            if (hostNativeLibsABI.exists()) {
                                File hostNativeLib = new File(hostNativeLibsABI, so.name)
                                if (hostNativeLib.exists()) {
                                    println(" update so: " + so + " >> " + hostNativeLibsABI)
                                    p.copy {
                                        from so
                                        into hostNativeLibsABI
                                    }
                                }
                            }
                        }
                    }
                    reassembleHostTask.doLast {
                        File srcPath = new File(dummyApk.parentFile, "${unzipHostApk.name}_unsign.apk")
                        File dstPath = dummyApk
                        String keyStore = packageApplication.signingConfig.storeFile.path
                        String storePass = packageApplication.signingConfig.storePassword
                        String alias = packageApplication.signingConfig.keyAlias
                        println "Sign apk, storeFile: " + keyStore + " storePass:" + storePass + " alias:" + alias + " source path: " + srcPath + ", destination path: " + dstPath
                        project.exec {
                            executable "jarsigner"
                            args "-keystore", keyStore, "-storepass", storePass, "-signedjar", dstPath, srcPath, alias
                        }
                    }
                }
            }
        }
    }

    private void hookDependenciesProject(Project project) {
        project.afterEvaluate {
            if (project.hasProperty("android") && project.android.class.name.find("com.android.build.gradle.LibraryExtension") != null) {
                project.android.packagingOptions {
                    doNotStrip "*/armeabi/*.so"
                    doNotStrip "*/armeabi-v7a/*.so"
                    doNotStrip "*/x86/*.so"
                }
                if (project.android.productFlavors.size() > 0) {
                    project.android.productFlavors.each { f->
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

    void createDummyHost(File dummyHostDir, String hostPackageName, String flavor, List dependencies, String launchActivity) {
        if (dummyHostDir.exists()) {
            return
        }else {
            dummyHostDir.mkdirs()
        }

        // create build.gradle
        File buildGradle = new File(dummyHostDir, "build.gradle")
        def pw = new PrintWriter(buildGradle.newWriter(false))
        pw.print("""apply plugin: 'com.android.application'
android { 
    compileSdkVersion 24 
    defaultConfig { 
        applicationId "${hostPackageName}" 
        minSdkVersion 23 
        targetSdkVersion 24 
        versionCode 1 
        versionName "1.0" 
        externalNativeBuild {
            ndkBuild {
                //abiFilters "armeabi-v7a"
            }
        }
    } 
    sourceSets {
        main {
            manifest.srcFile "\${projectDir}/AndroidManifest.xml"
        }
    }
    
    /*
    externalNativeBuild {
        ndkBuild {
            path 'Android.mk'
        }
    }
    */
    
    packagingOptions {
        doNotStrip "*/armeabi/*.so"
        doNotStrip "*/armeabi-v7a/*.so"
        doNotStrip "*/x86/*.so"
     }
""")
        if (flavor != null) {
            pw.println("""
    productFlavors {
        flavorDimensions 'default'
        ${flavor} {
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
                pw.println("    implementation project(':${it}')")
            }
            pw.println("}")
        }

        pw.flush()
        pw.close()

        // create AndroidManifest.xml
        File manifest = new File(dummyHostDir, "AndroidManifest.xml")
        pw = new PrintWriter(manifest.newWriter(false))
        pw.print("""<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="${hostPackageName}">
""")
        if (launchActivity != null) {
            pw.println("""
    <application>
        <activity android:name="${launchActivity}">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
""")
        }
        pw.println( "</manifest>")
        pw.flush()
        pw.close()


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

    private void getHostInfo() {
        if (mHostPackageName != null && mHostLaunchActivity != null) {
            return
        }
        if (mDummyHostDir.exists()) {
            try {
                File manifest = new File(mDummyHostDir, "AndroidManifest.xml")
                def manifestParser = new XmlParser(false, false).parse(manifest)
                mHostPackageName = manifestParser.@package
                mHostLaunchActivity = manifestParser.application.activity[0].@'android:name'
            }
            catch (Exception e) {

            }
            if (mHostPackageName != null && mHostLaunchActivity != null) {
                return
            }
        }
        if (mHostApk != null) {
            Properties properties = new Properties()
            properties.load(new File(settings.rootDir, 'local.properties').newDataInputStream())
            def sdkDir = properties.getProperty('sdk.dir')
            File aapt
            if (sdkDir != null) {
                File buildTools = new File(sdkDir, "build-tools")
                if (buildTools.exists()) {
                    buildTools.listFiles().find {
                        aapt = new File(it, "aapt.exe")
                        if (aapt.exists()) {
                            return true
                        }
                    }
                }
            }

            if (mHostPackageName == null || mHostLaunchActivity == null) {
                if (aapt != null && aapt.exists()) {
                    DefaultExecAction execAction = new DefaultExecAction(new MyPathToFileResolver())
                    execAction.setIgnoreExitValue(true)
                    def stdout = new ByteArrayOutputStream()
                    execAction.standardOutput = stdout
                    execAction.errorOutput = stdout
                    execAction.commandLine(aapt.path, "dump", "badging", mHostApk)
                    ExecResult result = execAction.execute()
                    if (result.exitValue == 0) {
                        List<String> apkInfo = stdout.toString().readLines()
                        String packageName = mHostPackageName
                        String launchActivity = mHostLaunchActivity
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
                        mHostPackageName = packageName
                        mHostLaunchActivity = launchActivity
                    } else {
                        println("run aapt.exe cmd failure " + mHostApk)
                    }
                } else {
                    println("can't find aapt.exe in " + sdkDir)
                }
            }
        }
    }

    private class MyPathToFileResolver implements PathToFileResolver {
        @Override
        File resolve(Object path) {
            return null
        }

        @Override
        Factory<File> resolveLater(Object path) {
            return new Factory<File>() {
                @Override
                File create() {
                    return settings.rootDir
                }
            }
        }

        @Override
        PathToFileResolver newResolver(File baseDir) {
            return null
        }
    }
}
