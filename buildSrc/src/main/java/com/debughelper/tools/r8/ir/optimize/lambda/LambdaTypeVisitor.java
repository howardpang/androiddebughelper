// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize.lambda;

import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.graph.DexValue.DexValueArray;
import com.debughelper.tools.r8.graph.DexValue.DexValueField;
import com.debughelper.tools.r8.graph.DexValue.DexValueMethod;
import com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle;
import com.debughelper.tools.r8.graph.DexValue.DexValueMethodType;
import com.debughelper.tools.r8.graph.DexValue.DexValueType;
import com.debughelper.tools.r8.graph.DexAnnotation;
import com.debughelper.tools.r8.graph.DexAnnotationElement;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexCallSite;
import com.debughelper.tools.r8.graph.DexEncodedAnnotation;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexMethodHandle;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexTypeList;
import com.debughelper.tools.r8.graph.ParameterAnnotationsList;

import java.util.function.Consumer;
import java.util.function.Predicate;

// Encapsulates the logic of visiting all lambda classes.
final class LambdaTypeVisitor {
  private final com.debughelper.tools.r8.graph.DexItemFactory factory;
  private final Predicate<com.debughelper.tools.r8.graph.DexType> isLambdaType;
  private final Consumer<com.debughelper.tools.r8.graph.DexType> onLambdaType;

  LambdaTypeVisitor(DexItemFactory factory,
                    Predicate<com.debughelper.tools.r8.graph.DexType> isLambdaType, Consumer<com.debughelper.tools.r8.graph.DexType> onLambdaType) {
    this.factory = factory;
    this.isLambdaType = isLambdaType;
    this.onLambdaType = onLambdaType;
  }

  void accept(DexCallSite callSite) {
    accept(callSite.methodProto);
    accept(callSite.bootstrapMethod);
    for (com.debughelper.tools.r8.graph.DexValue value : callSite.bootstrapArgs) {
      accept(value);
    }
  }

  private void accept(com.debughelper.tools.r8.graph.DexValue value) {
    if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueType) {
      accept(((com.debughelper.tools.r8.graph.DexValue.DexValueType) value).value);
      return;
    }
    if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueArray) {
      for (com.debughelper.tools.r8.graph.DexValue subValue : ((com.debughelper.tools.r8.graph.DexValue.DexValueArray) value).getValues()) {
        accept(subValue);
      }
      return;
    }
    if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueMethod) {
      accept(((com.debughelper.tools.r8.graph.DexValue.DexValueMethod) value).value, null);
      return;
    }
    if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle) {
      accept(((com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle) value).value);
      return;
    }
    if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueMethodType) {
      accept(((com.debughelper.tools.r8.graph.DexValue.DexValueMethodType) value).value);
      return;
    }
    if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueField) {
      accept(((com.debughelper.tools.r8.graph.DexValue.DexValueField) value).value, null);
    }
  }

  void accept(DexMethodHandle handle) {
    if (handle.isFieldHandle()) {
      accept(handle.asField(), null);
    } else {
      assert handle.isMethodHandle();
      accept(handle.asMethod(), null);
    }
  }

  void accept(DexField field, com.debughelper.tools.r8.graph.DexType holderToIgnore) {
    accept(field.type);
    if (holderToIgnore != field.clazz) {
      accept(field.clazz);
    }
  }

  void accept(DexMethod method, com.debughelper.tools.r8.graph.DexType holderToIgnore) {
    if (holderToIgnore != method.holder) {
      accept(method.holder);
    }
    accept(method.proto);
  }

  void accept(DexProto proto) {
    accept(proto.returnType);
    accept(proto.parameters);
  }

  void accept(DexTypeList types) {
    for (com.debughelper.tools.r8.graph.DexType type : types.values) {
      accept(type);
    }
  }

  void accept(DexAnnotationSet annotationSet) {
    for (com.debughelper.tools.r8.graph.DexAnnotation annotation : annotationSet.annotations) {
      accept(annotation);
    }
  }

  void accept(ParameterAnnotationsList parameterAnnotationsList) {
    parameterAnnotationsList.forEachAnnotation(this::accept);
  }

  private void accept(DexAnnotation annotation) {
    accept(annotation.annotation);
  }

  private void accept(DexEncodedAnnotation annotation) {
    accept(annotation.type);
    for (com.debughelper.tools.r8.graph.DexAnnotationElement element : annotation.elements) {
      accept(element);
    }
  }

  private void accept(DexAnnotationElement element) {
    accept(element.value);
  }

  void accept(DexType type) {
    if (type == null) {
      return;
    }
    if (type.isPrimitiveType() || type.isVoidType() || type.isPrimitiveArrayType()) {
      return;
    }
    if (type.isArrayType()) {
      accept(type.toArrayElementType(factory));
      return;
    }
    if (isLambdaType.test(type)) {
      onLambdaType.accept(type);
    }
  }
}
