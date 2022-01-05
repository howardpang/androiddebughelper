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

import com.android.annotations.NonNull
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.Format
import com.android.build.api.transform.Context
import com.android.build.api.transform.TransformException
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformInvocation
import com.android.build.api.transform.TransformOutputProvider
import com.android.build.gradle.internal.pipeline.TransformManager
import com.google.common.collect.ImmutableSet
import org.apache.commons.io.FileUtils

class CustomTransform extends Transform  {

    public Map<String, File> variantClassDir = new HashMap<>()

    @Override
    String getName() {
        return "DebugHelper"
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS;
    }

    @Override
    Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT
    }

    @Override
    boolean isIncremental() {
        return true
    }

    @Override
    void transform(@NonNull TransformInvocation transformInvocation)
            throws TransformException, InterruptedException, IOException {
        Context context = transformInvocation.getContext()

        Collection<TransformInput> inputs = transformInvocation.getInputs()
        TransformOutputProvider outputProvider = transformInvocation.getOutputProvider();
        File classDir = variantClassDir.get(context.getVariantName())
        if (classDir != null) {
            File dest = outputProvider.getContentLocation(
                    "debughelper", ImmutableSet.of(QualifiedContent.DefaultContentType.CLASSES), ImmutableSet.of(QualifiedContent.Scope.SUB_PROJECTS), Format.DIRECTORY)
            FileUtils.copyDirectory(classDir, dest)
        }

        inputs.each {
            // Bypass the directories
            it.directoryInputs.each { inputDir ->
                File dest = outputProvider.getContentLocation(
                        inputDir.name, inputDir.contentTypes, inputDir.scopes, Format.DIRECTORY)
            }

            // Filter the jars
            it.jarInputs.each {
                // Copy the jar and rename
                File src = it.file
                File dest = outputProvider.getContentLocation(
                        it.name, it.contentTypes, it.scopes, Format.JAR)
            }
        }
    }
}
