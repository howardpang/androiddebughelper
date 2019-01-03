## debughelper
debughelper plugin is a gradle plugin help to debug android lib module include native code
- android gradle plugin 3.0.0 - 3.2.0

[comment]: 
    ## Build and Test
    1.Android studio import this project  
    2.Enter 'gradlew publishToMavenLocal' command in Terminal or click 'publishToMavenLocal' task in gradle task list  
    3.Open *settings.gradle*, include 'app' project and build it  

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
        //hostPackageName="com.ydq.test.demo"
        //hookExternalBuild = true
        hostApk = "${rootDir}/test.apk"
        //hostFlavor = "audio"
        //hostLaunchActivity = "com.ydq.test.demo.MainActivity"
    }
    apply plugin: 'com.ydq.android.gradle.debug.helper'
### 2. Set host info 
* Set the debug apk path to *hostApk*
* Specify host launch activity name to *hostLaunchActivity* if your apk have multi-launch activity  
* Specify host flavor name to *hostFlavor* if your module have flavor
### 3. The plugin will create *dummyHost* app to debug, select the *dummyHost* to run/debug, the plugin will update the classes and native library in these project to the debug apk, and install the debug apk to run/debug  
    
    


