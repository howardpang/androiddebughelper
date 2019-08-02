// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize.lambda.kotlin;

import com.debughelper.tools.r8.ir.code.Invoke.Type;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.Invoke;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.synthetic.SyntheticSourceCode;
import com.google.common.collect.Lists;
import java.util.List;

final class ClassInitializerSourceCode extends SyntheticSourceCode {
  private final com.debughelper.tools.r8.graph.DexItemFactory factory;
  private final KotlinLambdaGroup group;

  ClassInitializerSourceCode(DexItemFactory factory, KotlinLambdaGroup group) {
    super(null, factory.createProto(factory.voidType));
    this.factory = factory;
    this.group = group;
  }

  @Override
  protected void prepareInstructions() {
    com.debughelper.tools.r8.graph.DexType groupClassType = group.getGroupClassType();
    DexMethod lambdaConstructorMethod = factory.createMethod(groupClassType,
        factory.createProto(factory.voidType, factory.intType), factory.constructorMethodName);

    int instance = nextRegister(com.debughelper.tools.r8.ir.code.ValueType.OBJECT);
    int lambdaId = nextRegister(com.debughelper.tools.r8.ir.code.ValueType.INT);
    List<com.debughelper.tools.r8.ir.code.ValueType> argTypes = Lists.newArrayList(com.debughelper.tools.r8.ir.code.ValueType.OBJECT, com.debughelper.tools.r8.ir.code.ValueType.INT);
    List<Integer> argRegisters = Lists.newArrayList(instance, lambdaId);

    group.forEachLambda(info -> {
      DexType lambda = info.clazz.type;
      if (group.isSingletonLambda(lambda)) {
        int id = group.lambdaId(lambda);
        add(builder -> builder.addNewInstance(instance, groupClassType));
        add(builder -> builder.addConst(ValueType.INT, lambdaId, id));
        add(builder -> builder.addInvoke(Invoke.Type.DIRECT,
            lambdaConstructorMethod, lambdaConstructorMethod.proto, argTypes, argRegisters));
        add(builder -> builder.addStaticPut(
            instance, group.getSingletonInstanceField(factory, id)));
      }
    });

    assert this.nextInstructionIndex() > 0 : "no single field initialized";
    add(IRBuilder::addReturn);
  }
}
