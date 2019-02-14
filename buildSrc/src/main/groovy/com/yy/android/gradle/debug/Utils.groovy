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

import com.android.build.gradle.internal.scope.VariantScope
import com.android.builder.core.AndroidBuilder
import com.android.builder.profile.ProcessProfileWriter
import org.gradle.api.Project
import org.gradle.util.VersionNumber

import java.lang.reflect.Field;

class Utils  {
    private static String androidPluginVersion

    static String androidGradleVersion() {
        if (androidPluginVersion == null) {
            try {
                Class<?> versionClass = Class.forName("com.android.builder.model.Version")
                Field versionField = versionClass.getField("ANDROID_GRADLE_PLUGIN_VERSION")
                androidPluginVersion = versionField.get(null)
            } catch(ClassNotFoundException e) {
                println(" unknown android plugin version ")
            }
            //androidPluginVersion = ProcessProfileWriter.getProject(project.getPath()).getAndroidPluginVersion()
            println(" android plugin version " + androidPluginVersion)
        }
        return androidPluginVersion
    }
}