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

import com.android.tools.r8.D8Command
import com.android.tools.r8.OutputMode
import com.android.tools.r8.ProgramResource
import com.android.tools.r8.graph.n
import org.gradle.api.Project
import java.nio.file.Path
import java.nio.file.Paths
import java.util.function.Consumer;

class R8DexParser3073 implements DexParser {
    // com.android.tools.r8.internal.getInternalOptions
    def internalOptions

    R8DexParser3073(Project project, int minApiLevel) {
        D8Command.Builder builderParse = D8Command.builder();
        builderParse.setMinApiLevel(minApiLevel)
        Path outputDir = Paths.get(project.buildDir.path)
        builderParse.setOutput(outputDir, OutputMode.DexIndexed)
        // D8Command.getInternalOptions
        internalOptions =  builderParse.build().b()
    }
    @Override
    Set<String> getTypeList(File dexFile) {
        Set<String> typeList = new HashSet<>()
        if (dexFile != null && dexFile.exists()) {
            Path dexPath = Paths.get(dexFile.path)

            // com.android.tools.r8.graph.DexProgramClass
            List<com.android.tools.r8.graph.Z> dexProgramClass = []

            ProgramResource dexResource = ProgramResource.fromFile(ProgramResource.Kind.DEX, dexPath)

            // com.android.tools.r8.dex.DexReader
            def dexReader = new com.android.tools.r8.dex.k(dexResource)

            // com.android.tools.r8.graph.ClassKind.PROGRAM
            def program = n.c

            // com.android.tools.r8.dex.DexParser
            def dexParser = new com.android.tools.r8.dex.j(dexReader, program, internalOptions)

            // com.android.tools.r8.dex.DexParser.populateIndexTables()
            dexParser.m()

            // com.android.tools.r8.dex.DexParser.addClassDefsTo()
            dexParser.a(new Consumer<Object>() {
                @Override
                void accept (Object s) {
                    dexProgramClass.add(s)
                }
            });

            dexProgramClass.each {
                // com.android.tools.r8.graph.DexClass.getType().getSimpleName()
                String typeName = it.getType().R()
                typeList.add(typeName)
            }
        }
        return typeList
    }
}
