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

import com.android.builder.model.SigningConfig
import com.android.ide.common.signing.CertificateInfo;
import com.android.ide.common.signing.KeystoreHelper
import com.google.common.base.Preconditions
import com.google.common.base.Optional;
import org.gradle.util.VersionNumber;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit

class ApkUpdater implements Closeable {
    static Class<?> sZFileOptionsClass
    static Class<?> sCreationDataClass
    static Class<?> sApkZFileCreatorFactoryClass
    static Class<?> sDeflateExecutionCompressorClass
    static Class<?> sBestAndDefaultDeflateExecutorCompressorClass
    static Class<?>  sSigningOptionsClass
    static Class<?>  sSigningOptionsValidationClass
    static def sNativeLibrariesPackagingMode_UNCOMPRESSED_AND_ALIGNED

    private def apkZFileCreator

    ApkUpdater(File apk, SigningConfig signingConfig, int minSdkVersion, boolean debug) {
        CertificateInfo certificateInfo = KeystoreHelper.getCertificateInfo(signingConfig.getStoreType(), (File) Preconditions.checkNotNull(signingConfig.getStoreFile()), (String) Preconditions.checkNotNull(signingConfig.getStorePassword()), (String) Preconditions.checkNotNull(signingConfig.getKeyPassword()), (String) Preconditions.checkNotNull(signingConfig.getKeyAlias()));
        def options
        def creationData
        def executionCompressor
        ThreadPoolExecutor compressionExecutor = new ThreadPoolExecutor(0, 2, 100L, TimeUnit.MILLISECONDS, new LinkedBlockingDeque())
        String curVersionString = Utils.androidGradleVersion()
        VersionNumber currentVersion = VersionNumber.parse(curVersionString)
        if (sZFileOptionsClass == null) {
            VersionNumber version320 = VersionNumber.parse("3.2.0")
            if (currentVersion >= version320) {
                sZFileOptionsClass = Class.forName("com.android.tools.build.apkzlib.zip.ZFileOptions")
                sCreationDataClass = Class.forName("com.android.tools.build.apkzlib.zfile.ApkCreatorFactory\$CreationData")
                sApkZFileCreatorFactoryClass = Class.forName("com.android.tools.build.apkzlib.zfile.ApkZFileCreatorFactory")
                sDeflateExecutionCompressorClass = Class.forName("com.android.tools.build.apkzlib.zip.compress.DeflateExecutionCompressor")
                sBestAndDefaultDeflateExecutorCompressorClass = Class.forName("com.android.tools.build.apkzlib.zip.compress.BestAndDefaultDeflateExecutorCompressor")
               sNativeLibrariesPackagingMode_UNCOMPRESSED_AND_ALIGNED = Class.forName("com.android.tools.build.apkzlib.zfile.NativeLibrariesPackagingMode").getEnumConstants()[1]
                if(currentVersion >= VersionNumber.parse("3.4.0")) {
                    sSigningOptionsClass = Class.forName("com.android.tools.build.apkzlib.sign.SigningOptions")
                    sSigningOptionsValidationClass = Class.forName("com.android.tools.build.apkzlib.sign.SigningOptions\$Validation")
                }
            } else {
                sZFileOptionsClass = Class.forName("com.android.apkzlib.zip.ZFileOptions")
                sCreationDataClass = Class.forName("com.android.apkzlib.zfile.ApkCreatorFactory\$CreationData")
                sApkZFileCreatorFactoryClass = Class.forName("com.android.apkzlib.zfile.ApkZFileCreatorFactory")
                sDeflateExecutionCompressorClass = Class.forName("com.android.apkzlib.zip.compress.DeflateExecutionCompressor")
                sBestAndDefaultDeflateExecutorCompressorClass = Class.forName("com.android.apkzlib.zip.compress.BestAndDefaultDeflateExecutorCompressor")
                sNativeLibrariesPackagingMode_UNCOMPRESSED_AND_ALIGNED = Class.forName("com.android.apkzlib.zfile.NativeLibrariesPackagingMode").getEnumConstants()[1]
            }
        }

        com.google.common.base.Predicate<String> noP = { path ->
            return false
        }
        options = sZFileOptionsClass.newInstance()
        if (debug) {
            executionCompressor = sDeflateExecutionCompressorClass.newInstance(compressionExecutor, options.getTracker(), 1)
        }else {
            executionCompressor = sBestAndDefaultDeflateExecutorCompressorClass.newInstance(compressionExecutor, options.getTracker(), 1.0D)
            options.setAutoSortFiles(true)
        }
        options.setNoTimestamps(true)
        options.setCoverEmptySpaceUsingExtraField(true)
        options.setCompressor(executionCompressor)

        if(currentVersion >= VersionNumber.parse("3.4.0")) {
            def signingOptions = Optional.of(sSigningOptionsClass.builder()
                    .setKey(certificateInfo.getKey())
                    .setCertificates(certificateInfo.getCertificate())
                    .setV1SigningEnabled(signingConfig.isV1SigningEnabled())
                    .setV2SigningEnabled(signingConfig.isV2SigningEnabled())
                    .setMinSdkVersion(minSdkVersion)
                    .setValidation(sSigningOptionsValidationClass.ALWAYS_VALIDATE)
                    .build());
            if(currentVersion >= VersionNumber.parse("3.5.0")) {
                creationData = sCreationDataClass.builder()
                        .setSigningOptions(signingOptions.get())
                        .setApkPath(apk)
                        .setBuiltBy("apkUpdater")
                        .setCreatedBy("apkUpdater")
                        .setNativeLibrariesPackagingMode(sNativeLibrariesPackagingMode_UNCOMPRESSED_AND_ALIGNED)
                        .build()

            }else {
                creationData = sCreationDataClass.newInstance(apk, signingOptions, "apkUpdater", "apkUpdater", sNativeLibrariesPackagingMode_UNCOMPRESSED_AND_ALIGNED, noP)
            }
        }else {
            creationData = sCreationDataClass.newInstance(apk, certificateInfo.key, certificateInfo.certificate, signingConfig.isV1SigningEnabled(), signingConfig.isV2SigningEnabled(), "apkUpdater", "apkUpdater", minSdkVersion, sNativeLibrariesPackagingMode_UNCOMPRESSED_AND_ALIGNED, noP)
        }
        apkZFileCreator = sApkZFileCreatorFactoryClass.newInstance(options).make(creationData)
    }

    void updateFiles(Map<File, String> files) {
        files.each { file, path ->
            println("update apk files " + file + " >> " + path)
            apkZFileCreator.writeFile(file, path)
        }
    }

    void deleteFile(String path) {
        apkZFileCreator.deleteFile(path)
        println("delete apk files " + path)
    }

    @Override
    void close() throws IOException {
        if (apkZFileCreator != null) {
            apkZFileCreator.close()
        }
    }
}
