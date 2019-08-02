// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize.lambda.kotlin;

import com.debughelper.tools.r8.ir.optimize.lambda.CodeProcessor.Strategy;
import com.debughelper.tools.r8.ir.optimize.lambda.kotlin.KotlinLambdaGroupCodeStrategy;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.optimize.lambda.CaptureSignature;
import com.debughelper.tools.r8.ir.optimize.lambda.CodeProcessor;
import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroup;
import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroupId;

// Represents a lambda group created to combine several lambda classes generated
// by kotlin compiler for either regular kotlin lambda expressions (k-style lambdas)
// or lambda expressions created to implement java SAM interface.
abstract class KotlinLambdaGroup extends LambdaGroup {
  private final CodeProcessor.Strategy strategy = new com.debughelper.tools.r8.ir.optimize.lambda.kotlin.KotlinLambdaGroupCodeStrategy(this);

  KotlinLambdaGroup(LambdaGroupId id) {
    super(id);
  }

  final KotlinLambdaGroupId id() {
    return (KotlinLambdaGroupId) id;
  }

  final boolean isStateless() {
    return id().capture.isEmpty();
  }

  final boolean hasAnySingletons() {
    assert isStateless();
    return anyLambda(info -> this.isSingletonLambda(info.clazz.type));
  }

  final boolean isSingletonLambda(com.debughelper.tools.r8.graph.DexType lambda) {
    assert isStateless();
    return lambdaSingletonField(lambda) != null;
  }

  // Field referencing singleton instance for a lambda with specified id.
  final com.debughelper.tools.r8.graph.DexField getSingletonInstanceField(com.debughelper.tools.r8.graph.DexItemFactory factory, int id) {
    return factory.createField(this.getGroupClassType(),
        this.getGroupClassType(), factory.createString("INSTANCE$" + id));
  }

  @Override
  protected String getTypePackage() {
    String pkg = id().pkg;
    return pkg.isEmpty() ? "" : (pkg + "/");
  }

  final DexProto createConstructorProto(com.debughelper.tools.r8.graph.DexItemFactory factory) {
    String capture = id().capture;
    com.debughelper.tools.r8.graph.DexType[] newParameters = new com.debughelper.tools.r8.graph.DexType[capture.length() + 1];
    newParameters[0] = factory.intType; // Lambda id.
    for (int i = 0; i < capture.length(); i++) {
      newParameters[i + 1] = com.debughelper.tools.r8.ir.optimize.lambda.CaptureSignature.fieldType(factory, capture, i);
    }
    return factory.createProto(factory.voidType, newParameters);
  }

  final com.debughelper.tools.r8.graph.DexField getLambdaIdField(com.debughelper.tools.r8.graph.DexItemFactory factory) {
    return factory.createField(this.getGroupClassType(), factory.intType, "$id$");
  }

  final int mapFieldIntoCaptureIndex(DexType lambda, com.debughelper.tools.r8.graph.DexField field) {
    return com.debughelper.tools.r8.ir.optimize.lambda.CaptureSignature.mapFieldIntoCaptureIndex(
        id().capture, lambdaCaptureFields(lambda), field);
  }

  final DexField getCaptureField(DexItemFactory factory, int index) {
    assert index >= 0 && index < id().capture.length();
    return factory.createField(this.getGroupClassType(),
        CaptureSignature.fieldType(factory, id().capture, index), "$capture$" + index);
  }

  @Override
  public CodeProcessor.Strategy getCodeStrategy() {
    return strategy;
  }
}
