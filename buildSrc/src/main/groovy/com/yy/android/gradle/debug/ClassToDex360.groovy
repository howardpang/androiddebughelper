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
package com.yy.android.gradle.debug;

import com.android.build.api.transform.Context;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.api.ApplicationVariantImpl;
import com.android.build.gradle.internal.tasks.DexArchiveBuilderTaskDelegate;
import org.gradle.api.Project;
import com.android.build.gradle.options.BooleanOption;
import com.android.build.gradle.options.IntegerOption;
import com.android.build.gradle.options.ProjectOptions;
import com.android.build.gradle.options.SyncOptions;
import com.android.builder.core.DexOptions;
import org.gradle.work.FileChange;
import org.gradle.workers.WorkerExecutor;
import com.android.ide.common.internal.WaitableExecutor;

class ClassToDex360  implements ClassToDex {
    private Context context;
    private ApplicationVariantImpl variant;
    private Project project;
    private final WorkerExecutor workerExecutor;
    private static int DEFAULT_BUFFER_SIZE_IN_KB = 100;
    private static int DEFAULT_NUM_BUCKETS = Math.max((int)(Runtime.getRuntime().availableProcessors() / 2), (int)(1));

    ClassToDex360(Context context, Project prj, ApplicationVariantImpl variant, WorkerExecutor workerExecutor) {
        this.context = context;
        this.variant = variant;
        this.project = prj;
        this.workerExecutor = workerExecutor;
    }

    void classToDex(File classDir, File outputDir) {
        VariantScope variantScope = variant.variantData.scope;
        GlobalScope globalScope = variant.variantData.scope.globalScope;
        DexOptions dexOptions = project.android.getDexOptions();
        Set<File> emptyFile = new HashSet<File>();
        Set<FileChange> emptyChangeFile = new HashSet<FileChange>();
        ProjectOptions projectOptions = globalScope.getProjectOptions();

        Integer inBufferSizeObj = projectOptions.get(IntegerOption.DEXING_READ_BUFFER_SIZE);
        Integer outBufferSizeObj = projectOptions.get(IntegerOption.DEXING_WRITE_BUFFER_SIZE);
        int inBufferSize = (inBufferSizeObj != null) ? inBufferSizeObj.intValue() : DEFAULT_BUFFER_SIZE_IN_KB * 1024;
        int outBufferSize  = (outBufferSizeObj != null) ? outBufferSizeObj.intValue() : DEFAULT_BUFFER_SIZE_IN_KB * 1024;

        Integer numOfBucketsObj = projectOptions.get(IntegerOption.DEXING_NUMBER_OF_BUCKETS);
        int numOfBuckets = (numOfBucketsObj != null) ? numOfBucketsObj.intValue() : DEFAULT_NUM_BUCKETS;
        DexArchiveBuilderTaskDelegate dexArchiveBuilderDelegate = new DexArchiveBuilderTaskDelegate(
                false, globalScope.getFilteredBootClasspath().getFiles(),
                project.files(classDir).getFiles(), emptyChangeFile,
                emptyFile, emptyChangeFile,
                emptyFile, emptyChangeFile,
                emptyFile, emptyChangeFile,
                outputDir, outputDir, outputDir, outputDir, outputDir,
                emptyFile, emptyChangeFile,
                SyncOptions.getErrorFormatMode(projectOptions),
                variantScope.getVariantConfiguration().getMinSdkVersionWithTargetDeviceApi().getFeatureLevel(),
                variantScope.getDexer(),
                projectOptions.get(BooleanOption.ENABLE_GRADLE_WORKERS),
                inBufferSize,
                outBufferSize,
                true,
                variantScope.getJava8LangSupportType(),
                "${variantScope.globalScope.project.name}:${variantScope.fullVariantName}",
                numOfBuckets,
                dexOptions.getAdditionalParameters().contains("--no-optimize"),
                "",
                globalScope.getMessageReceiver(),
                 WaitableExecutor.useGlobalSharedThreadPool(),
                null,
                workerExecutor);
        dexArchiveBuilderDelegate.doProcess();
    }
}
