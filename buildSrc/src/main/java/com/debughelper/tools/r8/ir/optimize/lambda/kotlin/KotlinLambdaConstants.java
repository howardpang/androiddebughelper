// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize.lambda.kotlin;

import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.graph.ClassAccessFlags;
import com.debughelper.tools.r8.graph.FieldAccessFlags;
import com.debughelper.tools.r8.graph.MethodAccessFlags;

interface KotlinLambdaConstants {
  // Default lambda class flags.
  com.debughelper.tools.r8.graph.ClassAccessFlags LAMBDA_CLASS_FLAGS =
      com.debughelper.tools.r8.graph.ClassAccessFlags.fromDexAccessFlags(com.debughelper.tools.r8.dex.Constants.ACC_FINAL);
  // Access-relaxed lambda class flags.
  com.debughelper.tools.r8.graph.ClassAccessFlags PUBLIC_LAMBDA_CLASS_FLAGS =
      ClassAccessFlags.fromDexAccessFlags(com.debughelper.tools.r8.dex.Constants.ACC_PUBLIC + com.debughelper.tools.r8.dex.Constants.ACC_FINAL);

  // Default lambda class initializer flags.
  com.debughelper.tools.r8.graph.MethodAccessFlags CLASS_INITIALIZER_FLAGS =
      com.debughelper.tools.r8.graph.MethodAccessFlags.fromSharedAccessFlags(com.debughelper.tools.r8.dex.Constants.ACC_STATIC, true);
  // Default lambda class constructor flags.
  com.debughelper.tools.r8.graph.MethodAccessFlags CONSTRUCTOR_FLAGS =
      com.debughelper.tools.r8.graph.MethodAccessFlags.fromSharedAccessFlags(0, true);
  // Access-relaxed lambda class constructor flags.
  com.debughelper.tools.r8.graph.MethodAccessFlags CONSTRUCTOR_FLAGS_RELAXED =
      com.debughelper.tools.r8.graph.MethodAccessFlags.fromSharedAccessFlags(com.debughelper.tools.r8.dex.Constants.ACC_PUBLIC, true);

  // Default main lambda method flags.
  com.debughelper.tools.r8.graph.MethodAccessFlags MAIN_METHOD_FLAGS =
      com.debughelper.tools.r8.graph.MethodAccessFlags.fromSharedAccessFlags(
          com.debughelper.tools.r8.dex.Constants.ACC_PUBLIC + com.debughelper.tools.r8.dex.Constants.ACC_FINAL, false);
  // Default bridge lambda method flags.
  com.debughelper.tools.r8.graph.MethodAccessFlags BRIDGE_METHOD_FLAGS =
      com.debughelper.tools.r8.graph.MethodAccessFlags.fromSharedAccessFlags(
          com.debughelper.tools.r8.dex.Constants.ACC_PUBLIC + com.debughelper.tools.r8.dex.Constants.ACC_SYNTHETIC + com.debughelper.tools.r8.dex.Constants.ACC_BRIDGE, false);
  // Bridge lambda method flags after inliner.
  com.debughelper.tools.r8.graph.MethodAccessFlags BRIDGE_METHOD_FLAGS_FIXED =
      MethodAccessFlags.fromSharedAccessFlags(com.debughelper.tools.r8.dex.Constants.ACC_PUBLIC, false);

  // Default singleton instance folding field flags.
  com.debughelper.tools.r8.graph.FieldAccessFlags SINGLETON_FIELD_FLAGS =
      com.debughelper.tools.r8.graph.FieldAccessFlags.fromSharedAccessFlags(
          com.debughelper.tools.r8.dex.Constants.ACC_PUBLIC + com.debughelper.tools.r8.dex.Constants.ACC_STATIC + com.debughelper.tools.r8.dex.Constants.ACC_FINAL);
  // Default instance (lambda capture) field flags.
  com.debughelper.tools.r8.graph.FieldAccessFlags CAPTURE_FIELD_FLAGS =
      com.debughelper.tools.r8.graph.FieldAccessFlags.fromSharedAccessFlags(
          com.debughelper.tools.r8.dex.Constants.ACC_FINAL + com.debughelper.tools.r8.dex.Constants.ACC_SYNTHETIC);
  // access-relaxed instance (lambda capture) field flags.
  com.debughelper.tools.r8.graph.FieldAccessFlags CAPTURE_FIELD_FLAGS_RELAXED =
      FieldAccessFlags.fromSharedAccessFlags(
          com.debughelper.tools.r8.dex.Constants.ACC_PUBLIC + com.debughelper.tools.r8.dex.Constants.ACC_FINAL + Constants.ACC_SYNTHETIC);
}
