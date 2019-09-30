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

import com.android.build.api.transform.Context
import com.android.build.api.transform.JarInput
import com.android.build.api.transform.QualifiedContent.ContentType;
import com.android.build.api.transform.QualifiedContent.Scope;
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider
import com.android.build.api.transform.Format
import com.android.build.gradle.internal.transforms.DexArchiveBuilderTransform
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.build.gradle.internal.pipeline.IntermediateFolderUtils
import com.android.build.gradle.internal.pipeline.TransformInvocationBuilder
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.api.ApplicationVariantImpl
import com.android.utils.FileUtils;
import org.gradle.api.Project;
import com.google.common.collect.ImmutableList
import com.google.common.base.MoreObjects;

class ClassToDex330 implements ClassToDex {
    private Context context
    private DexArchiveBuilderTransform customDexTransform

    ClassToDex330(Context context, Project prj, ApplicationVariantImpl variant) {
        this.context = context
        customDexTransform = GradleApiAdapter.createDexArchiveBuilderTransform(prj, variant)
    }

    void classToDex(File classDir, File outputDir) {
        ImmutableDirectoryInput directoryInput = new ImmutableDirectoryInput(classDir.name, classDir, TransformManager.CONTENT_CLASS, TransformManager.SCOPE_FULL_PROJECT)
        List<ImmutableDirectoryInput> directoryInputs = []
        directoryInputs.add(directoryInput)
        ImmutableTransformInput transformInput = new ImmutableTransformInput(ImmutableList.of(), directoryInputs, null)
        List<ImmutableTransformInput> transformInputs = []
        transformInputs.add(transformInput)
        TransformOutputProviderImpl transformOutputProvider = new TransformOutputProviderImpl(new IntermediateFolderUtils(outputDir, TransformManager.CONTENT_CLASS, TransformManager.SCOPE_FULL_PROJECT))
        TransformInvocation customTransformInvocation = new TransformInvocationBuilder(context)
                .addInputs(transformInputs)
                .addOutputProvider(transformOutputProvider)
                .build()

        customDexTransform.transform(customTransformInvocation)
    }


    // ************************* sub class **************************************

    class ImmutableTransformInput implements TransformInput {
        private File optionalRootLocation;
        private final Collection<JarInput> jarInputs;
        private final Collection<DirectoryInput> directoryInputs;

        ImmutableTransformInput(Collection<JarInput> jarInputs, Collection<DirectoryInput> directoryInputs, File optionalRootLocation) {
            this.jarInputs = ImmutableList.copyOf(jarInputs);
            this.directoryInputs = ImmutableList.copyOf(directoryInputs);
            this.optionalRootLocation = optionalRootLocation;
        }

        public Collection<JarInput> getJarInputs() {
            return this.jarInputs;
        }

        public Collection<DirectoryInput> getDirectoryInputs() {
            return this.directoryInputs;
        }

        public String toString() {
            return MoreObjects.toStringHelper(this).add("rootLocation", this.optionalRootLocation).add("jarInputs", this.jarInputs).add("folderInputs", this.directoryInputs).toString();
        }
    }

    class TransformOutputProviderImpl implements TransformOutputProvider {
        private final IntermediateFolderUtils folderUtils;

        TransformOutputProviderImpl(IntermediateFolderUtils folderUtils) {
            this.folderUtils = folderUtils;
        }

        public void deleteAll() throws IOException {
            FileUtils.cleanOutputDir(this.folderUtils.getRootFolder());
        }

        public File getContentLocation(String name, Set<ContentType> types, Set<? super Scope> scopes, Format format) {
            return this.folderUtils.getContentLocation(name, types, scopes, format);
        }
    }
}
