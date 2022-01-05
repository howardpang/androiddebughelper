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

import com.android.ide.common.process.ProcessOutput
import com.android.ide.common.process.ProcessOutputHandler

import java.nio.file.Files

class DxDexMerger implements DexMerger {
    com.android.dx.command.dexer.DxContext dxContext
    ProcessOutput output

    DxDexMerger(ProcessOutputHandler outputHandler) {
        this.output = outputHandler.createOutput()
        this.dxContext = new com.android.dx.command.dexer.DxContext(output.getStandardOutput(), output.getErrorOutput())
    }

    @Override
    Result merge(Set<File> dexesToUpdate, File dexFile, boolean keepFirst) {
        if (dexesToUpdate.isEmpty()) {
            return Result.FAILED
        }
        boolean dstDexExists = dexFile.exists()
        int dexesToMergeSize = dstDexExists ? (dexesToUpdate.size() + 1) : dexesToUpdate.size()
        if (dexesToMergeSize > 1) {
            com.android.dex.Dex[] dexesToMerge = new com.android.dex.Dex[dexesToMergeSize]
            int index = 0
            dexesToUpdate.each {
                dexesToMerge[index] = new com.android.dex.Dex(it)
                index++
            }
            if (dstDexExists) {
                dexesToMerge[index] = new com.android.dex.Dex(dexFile)
            }
            com.android.dx.merge.CollisionPolicy collisionPolicy = keepFirst ? com.android.dx.merge.CollisionPolicy.KEEP_FIRST : com.android.dx.merge.CollisionPolicy.FAIL
            com.android.dx.merge.DexMerger dexMerger =
                    new com.android.dx.merge.DexMerger(
                            dexesToMerge,
                            collisionPolicy,
                            dxContext)

            try {
                com.android.dex.Dex dexMerged = dexMerger.merge()
                Files.write(dexFile.toPath(), dexMerged.getBytes())
            } catch (com.android.dex.DexIndexOverflowException e) {
                return Result.OVER_FLOW
            } catch (Exception e) {
                println("DxDexMerger merge exception: " + e.toString())
                return Result.FAILED
            }
            return Result.SUCCEED
        }
    }

    @Override
    void close() {
        output.close()
    }
}
