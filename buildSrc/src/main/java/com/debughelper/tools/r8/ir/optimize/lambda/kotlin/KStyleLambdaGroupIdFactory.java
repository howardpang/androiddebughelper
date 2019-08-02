// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize.lambda.kotlin;

import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroup.LambdaStructureError;
import com.debughelper.tools.r8.ir.optimize.lambda.kotlin.KStyleLambdaGroup;
import com.debughelper.tools.r8.ir.optimize.lambda.kotlin.KotlinLambdaGroupIdFactory;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.InnerClassAttribute;
import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroup;
import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroupId;
import com.debughelper.tools.r8.kotlin.Kotlin;
import com.debughelper.tools.r8.utils.InternalOptions;

final class KStyleLambdaGroupIdFactory extends com.debughelper.tools.r8.ir.optimize.lambda.kotlin.KotlinLambdaGroupIdFactory {
  static final KotlinLambdaGroupIdFactory INSTANCE = new KStyleLambdaGroupIdFactory();

  @Override
  LambdaGroupId validateAndCreate(com.debughelper.tools.r8.kotlin.Kotlin kotlin, com.debughelper.tools.r8.graph.DexClass lambda, InternalOptions options)
      throws LambdaGroup.LambdaStructureError {
    boolean accessRelaxed = options.proguardConfiguration.isAccessModificationAllowed();

    assert lambda.hasKotlinInfo() && lambda.getKotlinInfo().isSyntheticClass();
    assert lambda.getKotlinInfo().asSyntheticClass().isKotlinStyleLambda();

    checkAccessFlags("class access flags", lambda.accessFlags,
        PUBLIC_LAMBDA_CLASS_FLAGS, LAMBDA_CLASS_FLAGS);

    // Class and interface.
    validateSuperclass(kotlin, lambda);
    com.debughelper.tools.r8.graph.DexType iface = validateInterfaces(kotlin, lambda);

    validateStaticFields(kotlin, lambda);
    String captureSignature = validateInstanceFields(lambda, accessRelaxed);
    validateDirectMethods(lambda);
    DexEncodedMethod mainMethod = validateVirtualMethods(lambda);
    String genericSignature = validateAnnotations(kotlin, lambda);
    InnerClassAttribute innerClass = validateInnerClasses(lambda);

    return new com.debughelper.tools.r8.ir.optimize.lambda.kotlin.KStyleLambdaGroup.GroupId(captureSignature, iface,
        accessRelaxed ? "" : lambda.type.getPackageDescriptor(),
        genericSignature, mainMethod, innerClass, lambda.getEnclosingMethod());
  }

  @Override
  void validateSuperclass(com.debughelper.tools.r8.kotlin.Kotlin kotlin, com.debughelper.tools.r8.graph.DexClass lambda) throws LambdaGroup.LambdaStructureError {
    if (lambda.superType != kotlin.functional.lambdaType) {
      throw new LambdaGroup.LambdaStructureError("implements " + lambda.superType.toSourceString() +
          " instead of kotlin.jvm.internal.Lambda");
    }
  }

  @Override
  com.debughelper.tools.r8.graph.DexType validateInterfaces(Kotlin kotlin, DexClass lambda) throws LambdaGroup.LambdaStructureError {
    if (lambda.interfaces.size() == 0) {
      throw new LambdaGroup.LambdaStructureError("does not implement any interfaces");
    }
    if (lambda.interfaces.size() > 1) {
      throw new LambdaGroup.LambdaStructureError(
          "implements more than one interface: " + lambda.interfaces.size());
    }
    DexType iface = lambda.interfaces.values[0];
    if (!kotlin.functional.isFunctionInterface(iface)) {
      throw new LambdaGroup.LambdaStructureError("implements " + iface.toSourceString() +
          " instead of kotlin functional interface.");
    }
    return iface;
  }
}
