## debughelper
debughelper plugin is a gradle plugin help to debug android lib module, the plugin can update java and native code to the host apk
## Requirement
* android gradle plugin: 3.0.0 - 3.6.2
* LLDB, if you want to debug native code: <https://developer.android.com/studio/debug/>
## Usage
### 1.Edit your root *setting.gradle* file, add these code to the **end of the file**
    buildscript {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath 'com.android.tools.build:gradle:3.0.0'
            //Add debughelper dependency
            classpath 'io.github.howardpang:debughelper:1.0.5'
        }
    }
    ext {
        hostApk = "${rootDir}/test.apk"
        //hostFlavor = "audio"
        //updateJavaClass = false
        //modifyApkDebuggable = false
        //hostLaunchActivity = "com.ydq.test.demo.MainActivity"
        //excludeSo = "libxx.so;libyy.so"
        //extraFilesToUpdate = [:] //Map[File:pathRelativeToApk]
        //extraFilesToUpdate.put(new File("${rootDir}/lib/armeabi-v7a/libextra.so"), "lib/armeabi-v7a/libextra.so")
    }
    apply plugin: 'com.ydq.android.gradle.debug.helper'
### 2. Make sure the android gradle plugin version 'com.android.tools.build:gradle:3.0.0' is the same with the root *build.gradle* and also add 'jcenter()' to repositories to the root *build.gradle*
### 3. Set host information
* Set the debug apk path to *hostApk*
* Specify host launch activity name to *hostLaunchActivity* if your apk have multi-launch activity  
* Specify host flavor name to *hostFlavor* if your module have flavor
* Specify *updateJavaClass* to decide whether to update java class, default is true, sometimes you may want to just update native code,so you can set it to false and the build will faster
* Specify *modifyApkDebuggable* to decide whether to modify the apk to debuggable, default is true, some app will check this flag and exist when they found the app is debuggable
* Specify *excludeSo*, if you want't to update some native library in to host apk
* If you have changed the *ext* settings or the *hostApk* file was changed, please clean the project
### 4. There are some limitations when you update java class
* It will only update the classes belong the project, not include the third party library
* It require the classes not obfuscated by the Proguard,include the third party library it depended
### 5. The plugin will create *dummyHost* app module to your project, select the *dummyHost* to run/debug, the plugin will update the classes and native library belong the project to the debug apk, and install the debug apk to run/debug  
### 6. If there some weird issue when you sync the project, please close the project and delete the *dummyHost* and *.idea* directory manually, then reopen the project
