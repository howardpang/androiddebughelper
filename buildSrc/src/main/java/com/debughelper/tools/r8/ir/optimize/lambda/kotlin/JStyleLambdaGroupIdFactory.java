// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize.lambda.kotlin;

import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroup.LambdaStructureError;
import com.debughelper.tools.r8.ir.optimize.lambda.kotlin.JStyleLambdaGroup;
import com.debughelper.tools.r8.ir.optimize.lambda.kotlin.KotlinLambdaGroupIdFactory;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.InnerClassAttribute;
import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroup;
import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroupId;
import com.debughelper.tools.r8.kotlin.Kotlin;
import com.debughelper.tools.r8.utils.InternalOptions;

final class JStyleLambdaGroupIdFactory extends KotlinLambdaGroupIdFactory {
  static final JStyleLambdaGroupIdFactory INSTANCE = new JStyleLambdaGroupIdFactory();

  @Override
  LambdaGroupId validateAndCreate(com.debughelper.tools.r8.kotlin.Kotlin kotlin, com.debughelper.tools.r8.graph.DexClass lambda, InternalOptions options)
      throws LambdaGroup.LambdaStructureError {
    boolean accessRelaxed = options.proguardConfiguration.isAccessModificationAllowed();

    assert lambda.hasKotlinInfo() && lambda.getKotlinInfo().isSyntheticClass();
    assert lambda.getKotlinInfo().asSyntheticClass().isJavaStyleLambda();

    checkAccessFlags("class access flags", lambda.accessFlags,
        KotlinLambdaConstants.PUBLIC_LAMBDA_CLASS_FLAGS, KotlinLambdaConstants.LAMBDA_CLASS_FLAGS);

    // Class and interface.
    validateSuperclass(kotlin, lambda);
    com.debughelper.tools.r8.graph.DexType iface = validateInterfaces(kotlin, lambda);

    validateStaticFields(kotlin, lambda);
    String captureSignature = validateInstanceFields(lambda, accessRelaxed);
    validateDirectMethods(lambda);
    DexEncodedMethod mainMethod = validateVirtualMethods(lambda);
    String genericSignature = validateAnnotations(kotlin, lambda);
    InnerClassAttribute innerClass = validateInnerClasses(lambda);

    return new com.debughelper.tools.r8.ir.optimize.lambda.kotlin.JStyleLambdaGroup.GroupId(captureSignature, iface,
        accessRelaxed ? "" : lambda.type.getPackageDescriptor(),
        genericSignature, mainMethod, innerClass, lambda.getEnclosingMethod());
  }

  @Override
  void validateSuperclass(com.debughelper.tools.r8.kotlin.Kotlin kotlin, com.debughelper.tools.r8.graph.DexClass lambda) throws LambdaGroup.LambdaStructureError {
    if (lambda.superType != kotlin.factory.objectType) {
      throw new LambdaGroup.LambdaStructureError("implements " + lambda.superType.toSourceString() +
          " instead of java.lang.Object");
    }
  }

  @Override
  DexType validateInterfaces(Kotlin kotlin, DexClass lambda) throws LambdaGroup.LambdaStructureError {
    if (lambda.interfaces.size() == 0) {
      throw new LambdaGroup.LambdaStructureError("does not implement any interfaces");
    }
    if (lambda.interfaces.size() > 1) {
      throw new LambdaGroup.LambdaStructureError(
          "implements more than one interface: " + lambda.interfaces.size());
    }

    // We don't validate that the interface is actually a functional interface,
    // since it may be desugared, or optimized in any other way which should not
    // affect lambda class merging.
    return lambda.interfaces.values[0];
  }
}
