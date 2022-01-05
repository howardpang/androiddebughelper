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

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8Command
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.OutputMode
import com.android.tools.r8.errors.DexFileOverflowDiagnostic
import com.google.common.collect.ImmutableMap
import org.apache.commons.io.FileUtils
import com.android.tools.r8.c

class R8DexMerger3073 implements DexMerger {
    final Map<String, Integer> inputOrdering
    final File tmpDir
    final int minApiLevel
    boolean isOverFlow
    DiagnosticsHandler diagnosticsHandler;

    R8DexMerger3073(File tmpDir, int minApiLevel) {
        this.inputOrdering = ImmutableMap.<String, Integer>builder()
                .put("", 1)
                .build()
        this.tmpDir = tmpDir
        if (!this.tmpDir.exists()) {
            this.tmpDir.mkdirs()
        }
        this.minApiLevel = minApiLevel
        this.diagnosticsHandler = new MyD8DiagnosticsHandler()
    }

    @Override
    Result merge(Set<File> dexesToUpdate, File dexFile, boolean keepFirst) {
        if (dexesToUpdate.isEmpty()) {
            return Result.FAILED
        }

        isOverFlow = false
        D8Command.Builder builder = D8Command.builder(diagnosticsHandler);
        builder.setDisableDesugaring(true);
        builder.setIncludeClassesChecksum(false);

        builder.setMinApiLevel(this.minApiLevel)
        builder.setMode(CompilationMode.DEBUG)
        builder.setOutput(tmpDir.toPath(), OutputMode.DexIndexed)
        builder.setIntermediate(false);

        dexesToUpdate.each {
            builder.addProgramFiles(it.toPath())
        }
        if (dexFile.exists()) {
            builder.addProgramFiles(dexFile.toPath())
        }

        D8Command d8Command = builder.build()

        try {
            //com.android.tools.r8.DexFileMergerHelper.run()
            c.a(d8Command, true, inputOrdering);
        }catch(Exception e) {
            if (isOverFlow) {
                return Result.OVER_FLOW
            }else {
                println("R8DexMerger3073 merge exception: " + e.toString())
                return Result.FAILED
            }
        }

        File mergeDex = new File(tmpDir, "classes.dex")
        if (mergeDex.exists()) {
            FileUtils.copyFile(mergeDex, dexFile)
        }else {
            println("R8DexMerger3073 merge no dex output " + tmpDir.listFiles().size())
        }
        File[] dexFiles = tmpDir.listFiles()
        if (dexFiles.size() > 1) {
            println("R8DexMerger3073 merge output more than one dexes: ")
            dexFiles.each {
                println(it)
            }
        }
        dexFiles.each {
            it.delete()
        }

        return Result.SUCCEED
    }

    @Override
    void close() {

    }

    class MyD8DiagnosticsHandler implements DiagnosticsHandler  {
        @Override
        void error(Diagnostic var1) {
            if (var1 instanceof DexFileOverflowDiagnostic) {
                DexFileOverflowDiagnostic overflowDiagnostic = (DexFileOverflowDiagnostic) var1;
                isOverFlow = true
            }else {
                System.err.println(var1.getDiagnosticMessage());
            }
        }
    }
}
