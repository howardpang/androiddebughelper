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

import org.gradle.api.Project
import org.gradle.api.internal.artifacts.dependencies.DefaultProjectDependency
import com.android.build.gradle.internal.api.BaseVariantImpl

class DependencyUtils {

    static void collectDependencyProjectClasses(Project prj, String variantName,
                                                def classInfo) {
        Set<DefaultProjectDependency> projectDepencies = []
        collectProjectDependencies(prj, projectDepencies)
        projectDepencies.each {
            BaseVariantImpl variant = it.dependencyProject.android.libraryVariants.find {it.name == variantName }
            File classesDir = variant.javaCompile.destinationDir
            classInfo[classesDir] = prj.fileTree(classesDir).exclude("**/R.class", "**/R\$*.class").files
        }
    }

    static void collectProjectDependencies(Project prj, def allDependencies ) {
        //Defining configuration names from which dependencies will be taken (debugCompile or releaseCompile and compile)
        prj.evaluate()
        def projectDenpendencies = []
        if (prj.configurations.findByName("compile")) {
            projectDenpendencies += prj.configurations['compile'].dependencies.withType(DefaultProjectDependency.class)
        }
        if (prj.configurations.findByName("implementation")) {
            projectDenpendencies += prj.configurations['implementation'].dependencies.withType(DefaultProjectDependency.class)
        }
        if (prj.configurations.findByName("api")) {
            projectDenpendencies += prj.configurations['api'].dependencies.withType(DefaultProjectDependency.class)
        }
        if (projectDenpendencies != null) {
            projectDenpendencies.each { depend ->
                if (allDependencies.find { addedNode -> addedNode.group == depend.group && addedNode.name == depend.name } == null) {
                    allDependencies.add(depend)
                    collectProjectDependencies(depend.dependencyProject, allDependencies)
                }
            }
        }
    }
}
