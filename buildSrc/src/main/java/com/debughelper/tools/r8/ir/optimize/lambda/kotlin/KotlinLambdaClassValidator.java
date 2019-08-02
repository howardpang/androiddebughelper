// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize.lambda.kotlin;

import com.debughelper.tools.r8.ir.optimize.Inliner.Reason;
import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroup.LambdaStructureError;
import com.debughelper.tools.r8.code.Format22c;
import com.debughelper.tools.r8.code.Instruction;
import com.debughelper.tools.r8.code.InvokeDirect;
import com.debughelper.tools.r8.code.InvokeDirectRange;
import com.debughelper.tools.r8.code.Iput;
import com.debughelper.tools.r8.code.IputBoolean;
import com.debughelper.tools.r8.code.IputByte;
import com.debughelper.tools.r8.code.IputChar;
import com.debughelper.tools.r8.code.IputObject;
import com.debughelper.tools.r8.code.IputShort;
import com.debughelper.tools.r8.code.IputWide;
import com.debughelper.tools.r8.code.NewInstance;
import com.debughelper.tools.r8.code.ReturnVoid;
import com.debughelper.tools.r8.code.SputObject;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.AppInfoWithSubtyping;
import com.debughelper.tools.r8.graph.Code;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.optimize.lambda.CaptureSignature;
import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroup;
import com.debughelper.tools.r8.kotlin.Kotlin;
import com.debughelper.tools.r8.utils.ThrowingConsumer;

// Encapsulates the logic of deep-checking of the lambda class assumptions.
//
// For k- and j-style lambdas we only check the code of class and instance
// initializers to ensure that their code performs no unexpected actions:
//
//  (a) Class initializer is only present for stateless lambdas and does
//      nothing expect instantiating the instance and storing it in
//      static instance field.
//
//  (b) Instance initializers stores all captured values in proper capture
//      fields and calls the super constructor passing arity to it.
abstract class KotlinLambdaClassValidator
    implements ThrowingConsumer<com.debughelper.tools.r8.graph.DexClass, LambdaGroup.LambdaStructureError> {

  static final String LAMBDA_INIT_CODE_VERIFICATION_FAILED =
      "instance initializer code verification failed";
  private static final String LAMBDA_CLINIT_CODE_VERIFICATION_FAILED =
      "static initializer code verification failed";

  final com.debughelper.tools.r8.kotlin.Kotlin kotlin;
  private final KotlinLambdaGroup group;
  private final com.debughelper.tools.r8.graph.AppInfoWithSubtyping appInfo;

  KotlinLambdaClassValidator(Kotlin kotlin, KotlinLambdaGroup group, AppInfoWithSubtyping appInfo) {
    this.kotlin = kotlin;
    this.group = group;
    this.appInfo = appInfo;
  }

  // Report a structure error.
  final LambdaGroup.LambdaStructureError structureError(String s) {
    return new LambdaGroup.LambdaStructureError(s, false);
  }

  @Override
  public void accept(com.debughelper.tools.r8.graph.DexClass lambda) throws LambdaGroup.LambdaStructureError {
    if (!CaptureSignature.getCaptureSignature(lambda.instanceFields()).equals(group.id().capture)) {
      throw structureError("capture signature was modified");
    }

    com.debughelper.tools.r8.graph.DexEncodedMethod classInitializer = null;
    com.debughelper.tools.r8.graph.DexEncodedMethod instanceInitializer = null;
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : lambda.directMethods()) {
      // We only check bodies of class and instance initializers since we don't expect to
      // see any static or private methods and all virtual methods will be translated into
      // same methods dispatching on lambda id to proper code.
      if (method.isClassInitializer()) {
        com.debughelper.tools.r8.graph.Code code = method.getCode();
        if (!(group.isStateless() && group.isSingletonLambda(lambda.type))) {
          throw structureError("static initializer on non-singleton lambda");
        }
        if (classInitializer != null || code == null || !code.isDexCode()) {
          throw structureError(LAMBDA_CLINIT_CODE_VERIFICATION_FAILED);
        }
        validateStatelessLambdaClassInitializer(lambda, code.asDexCode());
        classInitializer = method;

      } else if (method.isInstanceInitializer()) {
        com.debughelper.tools.r8.graph.Code code = method.getCode();
        if (instanceInitializer != null || code == null || !code.isDexCode()) {
          throw structureError(LAMBDA_INIT_CODE_VERIFICATION_FAILED);
        }
        validateInstanceInitializer(lambda, code.asDexCode());
        instanceInitializer = method;
      }
    }

    if (group.isStateless() && group.isSingletonLambda(lambda.type) && (classInitializer == null)) {
      throw structureError("missing static initializer on singleton lambda");
    }

    // This check is actually not required for lambda class merging, we only have to do
    // this because group class method composition piggybacks on inlining which has
    // assertions checking when we can inline force-inlined methods. So we double-check
    // these assertion never triggers.
    //
    // NOTE: the real type name for lambda group class is not generated yet, but we
    //       can safely use a fake one here.
    DexType fakeLambdaGroupType = kotlin.factory.createType(
        "L" + group.getTypePackage() + "-$$LambdaGroup$XXXX;");
    for (DexEncodedMethod method : lambda.virtualMethods()) {
      if (!method.isInliningCandidate(fakeLambdaGroupType, Reason.SIMPLE, appInfo)) {
        throw structureError("method " + method.method.toSourceString() +
            " is not inline-able into lambda group class");
      }
    }
  }

  abstract int getInstanceInitializerSize(com.debughelper.tools.r8.graph.DexEncodedField[] captures);

  abstract int validateInstanceInitializerEpilogue(
          com.debughelper.tools.r8.code.Instruction[] instructions, int index) throws LambdaGroup.LambdaStructureError;

  private void validateInstanceInitializer(com.debughelper.tools.r8.graph.DexClass lambda, com.debughelper.tools.r8.graph.Code code)
      throws LambdaGroup.LambdaStructureError {
    com.debughelper.tools.r8.graph.DexEncodedField[] captures = lambda.instanceFields();
    com.debughelper.tools.r8.code.Instruction[] instructions = code.asDexCode().instructions;
    int index = 0;

    if (instructions.length != getInstanceInitializerSize(captures)) {
      throw structureError(LAMBDA_INIT_CODE_VERIFICATION_FAILED);
    }

    // Capture field assignments: go through captured fields in assumed order
    // and ensure they are assigned values from appropriate parameters.
    index = validateInstanceInitializerParameterMapping(captures, instructions, index);

    // Check the constructor epilogue: a call to superclass constructor.
    index = validateInstanceInitializerEpilogue(instructions, index);
    assert index == instructions.length;
  }

  private int validateInstanceInitializerParameterMapping(com.debughelper.tools.r8.graph.DexEncodedField[] captures,
                                                          com.debughelper.tools.r8.code.Instruction[] instructions, int index) throws LambdaGroup.LambdaStructureError {
    int wideFieldsSeen = 0;
    for (DexEncodedField field : captures) {
      switch (field.field.type.toShorty()) {
        case 'Z':
          if (!(instructions[index] instanceof IputBoolean) ||
              (instructions[index].getField() != field.field) ||
              (((com.debughelper.tools.r8.code.Format22c) instructions[index]).A != (index + 1 + wideFieldsSeen))) {
            throw structureError(LAMBDA_INIT_CODE_VERIFICATION_FAILED);
          }
          break;

        case 'B':
          if (!(instructions[index] instanceof IputByte) ||
              (instructions[index].getField() != field.field) ||
              (((com.debughelper.tools.r8.code.Format22c) instructions[index]).A != (index + 1 + wideFieldsSeen))) {
            throw structureError(LAMBDA_INIT_CODE_VERIFICATION_FAILED);
          }
          break;

        case 'S':
          if (!(instructions[index] instanceof IputShort) ||
              (instructions[index].getField() != field.field) ||
              (((com.debughelper.tools.r8.code.Format22c) instructions[index]).A != (index + 1 + wideFieldsSeen))) {
            throw structureError(LAMBDA_INIT_CODE_VERIFICATION_FAILED);
          }
          break;

        case 'C':
          if (!(instructions[index] instanceof IputChar) ||
              (instructions[index].getField() != field.field) ||
              (((com.debughelper.tools.r8.code.Format22c) instructions[index]).A != (index + 1 + wideFieldsSeen))) {
            throw structureError(LAMBDA_INIT_CODE_VERIFICATION_FAILED);
          }
          break;

        case 'I':
        case 'F':
          if (!(instructions[index] instanceof Iput) ||
              (instructions[index].getField() != field.field) ||
              (((com.debughelper.tools.r8.code.Format22c) instructions[index]).A != (index + 1 + wideFieldsSeen))) {
            throw structureError(LAMBDA_INIT_CODE_VERIFICATION_FAILED);
          }
          break;

        case 'J':
        case 'D':
          if (!(instructions[index] instanceof IputWide) ||
              (instructions[index].getField() != field.field) ||
              (((com.debughelper.tools.r8.code.Format22c) instructions[index]).A != (index + 1 + wideFieldsSeen))) {
            throw structureError(LAMBDA_INIT_CODE_VERIFICATION_FAILED);
          }
          wideFieldsSeen++;
          break;

        case 'L':
          if (!(instructions[index] instanceof IputObject) ||
              (instructions[index].getField() != field.field) ||
              (((Format22c) instructions[index]).A != (index + 1 + wideFieldsSeen))) {
            throw structureError(LAMBDA_INIT_CODE_VERIFICATION_FAILED);
          }
          break;

        default:
          throw new Unreachable();
      }
      index++;
    }
    return index;
  }

  private void validateStatelessLambdaClassInitializer(com.debughelper.tools.r8.graph.DexClass lambda, Code code)
      throws LambdaGroup.LambdaStructureError {
    assert group.isStateless() && group.isSingletonLambda(lambda.type);
    Instruction[] instructions = code.asDexCode().instructions;
    if (instructions.length != 4) {
      throw structureError(LAMBDA_CLINIT_CODE_VERIFICATION_FAILED);
    }
    if (!(instructions[0] instanceof NewInstance) ||
        ((NewInstance) instructions[0]).getType() != lambda.type) {
      throw structureError(LAMBDA_CLINIT_CODE_VERIFICATION_FAILED);
    }
    if (!(instructions[1] instanceof InvokeDirect
            || instructions[1] instanceof InvokeDirectRange)
        || !isLambdaInitializerMethod(lambda, instructions[1].getMethod())) {
      throw structureError(LAMBDA_CLINIT_CODE_VERIFICATION_FAILED);
    }
    if (!(instructions[2] instanceof SputObject) ||
        !isLambdaSingletonField(lambda, instructions[2].getField())) {
      throw structureError(LAMBDA_CLINIT_CODE_VERIFICATION_FAILED);
    }
    if (!(instructions[3] instanceof ReturnVoid)) {
      throw structureError(LAMBDA_CLINIT_CODE_VERIFICATION_FAILED);
    }
  }

  private boolean isLambdaSingletonField(com.debughelper.tools.r8.graph.DexClass lambda, DexField field) {
    return field.type == lambda.type && field.clazz == lambda.type &&
        field.name == kotlin.functional.kotlinStyleLambdaInstanceName;
  }

  private boolean isLambdaInitializerMethod(DexClass holder, DexMethod method) {
    return method.holder == holder.type && method.name == kotlin.factory.constructorMethodName &&
        method.proto.parameters.isEmpty() && method.proto.returnType == kotlin.factory.voidType;
  }
}
