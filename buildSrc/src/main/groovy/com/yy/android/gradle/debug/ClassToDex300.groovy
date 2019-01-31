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

import com.android.build.gradle.internal.api.ApplicationVariantImpl
import com.android.build.gradle.internal.scope.VariantScope
import com.android.builder.core.AndroidBuilder
import org.gradle.api.Project
import com.android.ide.common.process.ProcessOutputHandler;

class ClassToDex300 implements ClassToDex {
    private ApplicationVariantImpl variant
    private def dexByteCodeConverter
    private def dexOptions
    private ProcessOutputHandler outputHandler
    private int minSdkVersion

    ClassToDex300(Project prj, ApplicationVariantImpl variant, ProcessOutputHandler outputHandler) {
        this.variant = variant
        this.outputHandler = outputHandler
        VariantScope scope = variant.variantData.scope
        AndroidBuilder androidBuilder = scope.getGlobalScope().androidBuilder
        dexOptions = prj.android.getDexOptions()
        dexByteCodeConverter = androidBuilder.getDexByteCodeConverter()
        minSdkVersion = scope.getMinSdkVersion().getFeatureLevel()
    }

    void classToDex(File classDir, File outputDir) {
        List<File> transformInputs = [classDir]
        dexByteCodeConverter.convertByteCode(
                transformInputs,
                outputDir,
                false,
                null,
                dexOptions,
                outputHandler,
                minSdkVersion);
    }
}
