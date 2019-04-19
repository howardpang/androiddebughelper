## debughelper
debughelper plugin is a gradle plugin help to debug android lib module, the plugin can update java and native code to the host apk
## Requirement
* android gradle plugin: 3.0.0 - 3.3.0
* LLDB, if you want to debug native code: <https://developer.android.com/studio/debug/>
## Usage
### 1.Edit your root *setting.gradle* file, add these code to the **end of the file**
    buildscript {
        repositories {
            jcenter()
            google()
        }
        dependencies {
            classpath 'com.android.tools.build:gradle:3.0.0'
            //Add debughelper dependency
            classpath 'com.ydq.android.gradle.build.tool:debughelper:1.0.0'
        }
    }
    ext {
        hostApk = "${rootDir}/test.apk"
        //hostFlavor = "audio"
        //updateJavaClass = false
        //modifyApkDebuggable = false
        //hostLaunchActivity = "com.ydq.test.demo.MainActivity"
    }
    apply plugin: 'com.ydq.android.gradle.debug.helper'
### 2. Set host information
* Set the debug apk path to *hostApk*
* Specify host launch activity name to *hostLaunchActivity* if your apk have multi-launch activity  
* Specify host flavor name to *hostFlavor* if your module have flavor
* Specify *updateJavaClass* to decide whether to update java class, default is true, sometimes you may want to just update native code,so you can set it to false and the build will faster
* Specify *modifyApkDebuggable* to decide whether to modify the apk to debuggable, default is true, some app will check this flag and exist when they found the app is debuggable
### 3. The plugin will create *dummyHost* app module to your project, select the *dummyHost* to run/debug, the plugin will update the classes and native library belong the project to the debug apk, and install the debug apk to run/debug  
