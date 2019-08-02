// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize.lambda.kotlin;

import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroup.LambdaStructureError;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.AccessFlags;
import com.debughelper.tools.r8.graph.DexAnnotation;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.InnerClassAttribute;
import com.debughelper.tools.r8.ir.optimize.lambda.CaptureSignature;
import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroup;
import com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroupId;
import com.debughelper.tools.r8.kotlin.Kotlin;
import com.debughelper.tools.r8.utils.InternalOptions;

import java.util.List;

public abstract class KotlinLambdaGroupIdFactory implements KotlinLambdaConstants {
  KotlinLambdaGroupIdFactory() {
  }

  // Creates a lambda group id for kotlin style lambda. Should never return null, if the lambda
  // does not pass pre-requirements (mostly by not meeting high-level structure expectations)
  // should throw LambdaStructureError leaving the caller to decide if/how it needs to be reported.
  //
  // At this point we only perform high-level checks before qualifying the lambda as a candidate
  // for merging and assigning lambda group id. We can NOT perform checks on method bodies since
  // they may not be converted yet, we'll do that in KStyleLambdaClassValidator.
  public static com.debughelper.tools.r8.ir.optimize.lambda.LambdaGroupId create(com.debughelper.tools.r8.kotlin.Kotlin kotlin, com.debughelper.tools.r8.graph.DexClass lambda, com.debughelper.tools.r8.utils.InternalOptions options)
      throws LambdaGroup.LambdaStructureError {

    assert lambda.hasKotlinInfo() && lambda.getKotlinInfo().isSyntheticClass();
    if (lambda.getKotlinInfo().asSyntheticClass().isKotlinStyleLambda()) {
      return KStyleLambdaGroupIdFactory.INSTANCE.validateAndCreate(kotlin, lambda, options);
    }

    assert lambda.getKotlinInfo().asSyntheticClass().isJavaStyleLambda();
    return JStyleLambdaGroupIdFactory.INSTANCE.validateAndCreate(kotlin, lambda, options);
  }

  abstract LambdaGroupId validateAndCreate(com.debughelper.tools.r8.kotlin.Kotlin kotlin, com.debughelper.tools.r8.graph.DexClass lambda, InternalOptions options)
      throws LambdaGroup.LambdaStructureError;

  abstract void validateSuperclass(com.debughelper.tools.r8.kotlin.Kotlin kotlin, com.debughelper.tools.r8.graph.DexClass lambda) throws LambdaGroup.LambdaStructureError;

  abstract com.debughelper.tools.r8.graph.DexType validateInterfaces(com.debughelper.tools.r8.kotlin.Kotlin kotlin, com.debughelper.tools.r8.graph.DexClass lambda) throws LambdaGroup.LambdaStructureError;

  com.debughelper.tools.r8.graph.DexEncodedMethod validateVirtualMethods(com.debughelper.tools.r8.graph.DexClass lambda) throws LambdaGroup.LambdaStructureError {
    com.debughelper.tools.r8.graph.DexEncodedMethod mainMethod = null;

    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : lambda.virtualMethods()) {
      if (method.accessFlags.equals(MAIN_METHOD_FLAGS)) {
        if (mainMethod != null) {
          throw new LambdaGroup.LambdaStructureError("more than one main method found");
        }
        mainMethod = method;
      } else {
        checkAccessFlags("unexpected virtual method access flags",
            method.accessFlags, BRIDGE_METHOD_FLAGS, BRIDGE_METHOD_FLAGS_FIXED);
        checkDirectMethodAnnotations(method);
      }
    }

    if (mainMethod == null) {
      // Missing main method may be a result of tree shaking.
      throw new LambdaGroup.LambdaStructureError("no main method found", false);
    }
    return mainMethod;
  }

  com.debughelper.tools.r8.graph.InnerClassAttribute validateInnerClasses(com.debughelper.tools.r8.graph.DexClass lambda) throws LambdaGroup.LambdaStructureError {
    List<com.debughelper.tools.r8.graph.InnerClassAttribute> innerClasses = lambda.getInnerClasses();
    if (innerClasses != null) {
      for (InnerClassAttribute inner : innerClasses) {
        if (inner.getInner() == lambda.type) {
          if (!inner.isAnonymous()) {
            throw new LambdaGroup.LambdaStructureError("is not anonymous");
          }
          return inner;
        }
      }
    }
    return null;
  }

  String validateAnnotations(com.debughelper.tools.r8.kotlin.Kotlin kotlin, com.debughelper.tools.r8.graph.DexClass lambda) throws LambdaGroup.LambdaStructureError {
    String signature = null;
    if (!lambda.annotations.isEmpty()) {
      for (com.debughelper.tools.r8.graph.DexAnnotation annotation : lambda.annotations.annotations) {
        if (com.debughelper.tools.r8.graph.DexAnnotation.isSignatureAnnotation(annotation, kotlin.factory)) {
          signature = DexAnnotation.getSignature(annotation);
          continue;
        }

        if (annotation.annotation.type == kotlin.metadata.kotlinMetadataType) {
          // Ignore kotlin metadata on lambda classes. Metadata on synthetic
          // classes exists but is not used in the current Kotlin version (1.2.21)
          // and newly generated lambda _group_ class is not exactly a kotlin class.
          continue;
        }

        throw new LambdaGroup.LambdaStructureError(
            "unexpected annotation: " + annotation.annotation.type.toSourceString());
      }
    }
    return signature;
  }

  void validateStaticFields(Kotlin kotlin, com.debughelper.tools.r8.graph.DexClass lambda) throws LambdaGroup.LambdaStructureError {
    com.debughelper.tools.r8.graph.DexEncodedField[] staticFields = lambda.staticFields();
    if (staticFields.length == 1) {
      com.debughelper.tools.r8.graph.DexEncodedField field = staticFields[0];
      if (field.field.name != kotlin.functional.kotlinStyleLambdaInstanceName ||
          field.field.type != lambda.type || !field.accessFlags.isPublic() ||
          !field.accessFlags.isFinal() || !field.accessFlags.isStatic()) {
        throw new LambdaGroup.LambdaStructureError("unexpected static field " + field.toSourceString());
      }
      // No state if the lambda is a singleton.
      if (lambda.instanceFields().length > 0) {
        throw new LambdaGroup.LambdaStructureError("has instance fields along with INSTANCE");
      }
      checkAccessFlags("static field access flags", field.accessFlags, SINGLETON_FIELD_FLAGS);
      checkFieldAnnotations(field);

    } else if (staticFields.length > 1) {
      throw new LambdaGroup.LambdaStructureError(
          "only one static field max expected, found " + staticFields.length);
    }
  }

  String validateInstanceFields(com.debughelper.tools.r8.graph.DexClass lambda, boolean accessRelaxed)
      throws LambdaGroup.LambdaStructureError {
    com.debughelper.tools.r8.graph.DexEncodedField[] instanceFields = lambda.instanceFields();
    for (com.debughelper.tools.r8.graph.DexEncodedField field : instanceFields) {
      checkAccessFlags("capture field access flags", field.accessFlags,
          accessRelaxed ? CAPTURE_FIELD_FLAGS_RELAXED : CAPTURE_FIELD_FLAGS);
      checkFieldAnnotations(field);
    }
    return CaptureSignature.getCaptureSignature(instanceFields);
  }

  void validateDirectMethods(DexClass lambda) throws LambdaGroup.LambdaStructureError {
    com.debughelper.tools.r8.graph.DexEncodedMethod[] directMethods = lambda.directMethods();
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : directMethods) {
      if (method.isClassInitializer()) {
        // We expect to see class initializer only if there is a singleton field.
        if (lambda.staticFields().length != 1) {
          throw new LambdaGroup.LambdaStructureError("has static initializer, but no singleton field");
        }
        checkAccessFlags("unexpected static initializer access flags",
            method.accessFlags, CLASS_INITIALIZER_FLAGS);
        checkDirectMethodAnnotations(method);

      } else if (method.isStaticMethod()) {
        throw new LambdaGroup.LambdaStructureError(
            "unexpected static method: " + method.method.toSourceString());

      } else if (method.isInstanceInitializer()) {
        // Lambda class is expected to have one constructor
        // with parameters matching capture signature.
        DexType[] parameters = method.method.proto.parameters.values;
        com.debughelper.tools.r8.graph.DexEncodedField[] instanceFields = lambda.instanceFields();
        if (parameters.length != instanceFields.length) {
          throw new LambdaGroup.LambdaStructureError("constructor parameters don't match captured values.");
        }
        for (int i = 0; i < parameters.length; i++) {
          // Kotlin compiler sometimes reshuffles the parameters so that their order
          // in the constructor don't match order of capture fields. We could add
          // support for it, but it happens quite rarely so don't bother for now.
          if (parameters[i] != instanceFields[i].field.type) {
            throw new LambdaGroup.LambdaStructureError(
                "constructor parameters don't match captured values.", false);
          }
        }
        checkAccessFlags("unexpected constructor access flags",
            method.accessFlags, CONSTRUCTOR_FLAGS, CONSTRUCTOR_FLAGS_RELAXED);
        checkDirectMethodAnnotations(method);

      } else {
        throw new Unreachable();
      }
    }
  }

  void checkDirectMethodAnnotations(DexEncodedMethod method) throws LambdaGroup.LambdaStructureError {
    if (!method.annotations.isEmpty()) {
      throw new LambdaGroup.LambdaStructureError("unexpected method annotations [" +
          method.annotations.toSmaliString() + "] on " + method.method.toSourceString());
    }
    if (!method.parameterAnnotationsList.isEmpty()) {
      throw new LambdaGroup.LambdaStructureError("unexpected method parameters annotations [" +
          method.annotations.toSmaliString() + "] on " + method.method.toSourceString());
    }
  }

  private static void checkFieldAnnotations(DexEncodedField field) throws LambdaGroup.LambdaStructureError {
    if (!field.annotations.isEmpty()) {
      throw new LambdaGroup.LambdaStructureError("unexpected field annotations [" +
          field.annotations.toSmaliString() + "] on " + field.field.toSourceString());
    }
  }

  @SafeVarargs
  static <T extends AccessFlags> void checkAccessFlags(
      String message, T actual, T... expected) throws LambdaGroup.LambdaStructureError {
    for (T flag : expected) {
      if (flag.equals(actual)) {
        return;
      }
    }
    throw new LambdaGroup.LambdaStructureError(message);
  }
}
