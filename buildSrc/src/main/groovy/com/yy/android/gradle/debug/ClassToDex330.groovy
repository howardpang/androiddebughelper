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
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.QualifiedContent.ContentType;
import com.android.build.api.transform.QualifiedContent.Scope;
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.Status
import com.android.build.api.transform.DirectoryInput
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider
import com.android.build.api.transform.Format
import com.android.build.gradle.internal.transforms.DexArchiveBuilderTransform
import com.android.build.gradle.internal.transforms.DexArchiveBuilderTransformBuilder
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.build.gradle.internal.pipeline.IntermediateFolderUtils
import com.android.build.gradle.internal.pipeline.TransformInvocationBuilder
import com.android.build.gradle.internal.scope.VariantScope
import com.android.build.gradle.internal.api.ApplicationVariantImpl
import com.android.build.gradle.options.BooleanOption;
import com.android.build.gradle.options.IntegerOption;
import com.android.build.gradle.options.ProjectOptions;
import com.android.builder.core.AndroidBuilder
import com.android.builder.core.DexOptions
import com.android.utils.FileUtils;
import org.gradle.api.Project;
import java.util.function.Supplier
import com.google.common.collect.ImmutableList
import com.google.common.base.MoreObjects;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

class ClassToDex330 implements ClassToDex {
    private Context context
    private ApplicationVariantImpl variant
    private DexArchiveBuilderTransform customDexTransform

    ClassToDex330(Context context, Project prj, ApplicationVariantImpl variant) {
        this.variant = variant
        this.context = context
        DexOptions dexOptions = prj.android.getDexOptions()
        VariantScope scope = variant.variantData.scope
        AndroidBuilder androidBuilder = scope.getGlobalScope().androidBuilder
        int minSdkVersion = scope.getMinSdkVersion().getFeatureLevel()
        ProjectOptions projectOptions = new ProjectOptions(prj)

        customDexTransform = (new DexArchiveBuilderTransformBuilder())
                .setAndroidJarClasspath(new Supplier<List<File>>() {
            @Override
            List<File> get() {
                androidBuilder.getBootClasspath(false)
            }
        })
                .setDexOptions((DexOptions) dexOptions)
                .setMessageReceiver(scope.getGlobalScope().getMessageReceiver())
                .setUserLevelCache(null)
                .setMinSdkVersion(minSdkVersion)
                .setDexer(scope.getDexer())
                .setUseGradleWorkers(projectOptions.get(BooleanOption.ENABLE_GRADLE_WORKERS))
                .setInBufferSize(projectOptions.get(IntegerOption.DEXING_READ_BUFFER_SIZE))
                .setOutBufferSize(projectOptions.get(IntegerOption.DEXING_WRITE_BUFFER_SIZE))
                .setIsDebuggable(true)
                .setJava8LangSupportType(scope.getJava8LangSupportType())
                .setProjectVariant(getProjectVariantId(scope))
                .setNumberOfBuckets(projectOptions.get(IntegerOption.DEXING_NUMBER_OF_BUCKETS))
                .setIncludeFeaturesInScope(scope.consumesFeatureJars())
                .setIsInstantRun(false)
                .setEnableDexingArtifactTransform(true)
                .createDexArchiveBuilderTransform()

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

    private static String getProjectVariantId(VariantScope variantScope) {
        return variantScope.getGlobalScope().getProject().getName() + ":" + variantScope.getFullVariantName();
    }

    // ************************* sub class **************************************

    class QualifiedContentImpl implements QualifiedContent, Serializable {
        private final String name;
        private final File file;
        private final Set<ContentType> contentTypes;
        private final Set<? super Scope> scopes;

        protected QualifiedContentImpl(String name, File file, Set<ContentType> contentTypes, Set<? super Scope> scopes) {
            this.name = name;
            this.file = file;
            this.contentTypes = ImmutableSet.copyOf(contentTypes);
            this.scopes = ImmutableSet.copyOf(scopes);
        }

        protected QualifiedContentImpl(QualifiedContent qualifiedContent) {
            this.name = qualifiedContent.getName();
            this.file = qualifiedContent.getFile();
            this.contentTypes = qualifiedContent.getContentTypes();
            this.scopes = qualifiedContent.getScopes();
        }

        public String getName() {
            return this.name;
        }

        public File getFile() {
            return this.file;
        }

        public Set<ContentType> getContentTypes() {
            return this.contentTypes;
        }

        public Set<? super Scope> getScopes() {
            return this.scopes;
        }

        public String toString() {
            return MoreObjects.toStringHelper(this).add("name", this.name).add("file", this.file).add("contentTypes", this.contentTypes).add("scopes", this.scopes).toString();
        }
    }

    class ImmutableDirectoryInput extends QualifiedContentImpl implements DirectoryInput {
        private final Map<File, Status> changedFiles;

        ImmutableDirectoryInput(String name, File file, Set<ContentType> contentTypes, Set<? super Scope> scopes) {
            super(name, file, contentTypes, scopes);
            this.changedFiles = ImmutableMap.of();
        }

        protected ImmutableDirectoryInput(String name, File file, Set<ContentType> contentTypes, Set<? super Scope> scopes, Map<File, Status> changedFiles) {
            super(name, file, contentTypes, scopes);
            this.changedFiles = ImmutableMap.copyOf(changedFiles);
        }

        public Map<File, Status> getChangedFiles() {
            return this.changedFiles;
        }

        public String toString() {
            return MoreObjects.toStringHelper(this).add("name", this.getName()).add("file", this.getFile()).add("contentTypes", Joiner.on(',').join(this.getContentTypes())).add("scopes", Joiner.on(',').join(this.getScopes())).add("changedFiles", this.changedFiles).toString();
        }
    }

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
