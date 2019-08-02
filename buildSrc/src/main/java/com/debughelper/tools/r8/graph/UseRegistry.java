// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexMethodHandle;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.graph.DexValue.DexValueDouble;
import com.debughelper.tools.r8.graph.DexValue.DexValueFloat;
import com.debughelper.tools.r8.graph.DexValue.DexValueInt;
import com.debughelper.tools.r8.graph.DexValue.DexValueLong;
import com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle;
import com.debughelper.tools.r8.graph.DexValue.DexValueMethodType;
import com.debughelper.tools.r8.graph.DexValue.DexValueString;
import com.debughelper.tools.r8.graph.DexValue.DexValueType;

public abstract class UseRegistry {

  public abstract boolean registerInvokeVirtual(DexMethod method);

  public abstract boolean registerInvokeDirect(DexMethod method);

  public abstract boolean registerInvokeStatic(DexMethod method);

  public abstract boolean registerInvokeInterface(DexMethod method);

  public abstract boolean registerInvokeSuper(DexMethod method);

  public abstract boolean registerInstanceFieldWrite(com.debughelper.tools.r8.graph.DexField field);

  public abstract boolean registerInstanceFieldRead(com.debughelper.tools.r8.graph.DexField field);

  public abstract boolean registerNewInstance(com.debughelper.tools.r8.graph.DexType type);

  public abstract boolean registerStaticFieldRead(com.debughelper.tools.r8.graph.DexField field);

  public abstract boolean registerStaticFieldWrite(DexField field);

  public abstract boolean registerTypeReference(com.debughelper.tools.r8.graph.DexType type);

  public boolean registerConstClass(com.debughelper.tools.r8.graph.DexType type) {
    return registerTypeReference(type);
  }

  public boolean registerCheckCast(com.debughelper.tools.r8.graph.DexType type) {
    return registerTypeReference(type);
  }

  public void registerMethodHandle(DexMethodHandle methodHandle) {
    switch (methodHandle.type) {
      case INSTANCE_GET:
        registerInstanceFieldRead(methodHandle.asField());
        break;
      case INSTANCE_PUT:
        registerInstanceFieldWrite(methodHandle.asField());
        break;
      case STATIC_GET:
        registerStaticFieldRead(methodHandle.asField());
        break;
      case STATIC_PUT:
        registerStaticFieldWrite(methodHandle.asField());
        break;
      case INVOKE_INSTANCE:
        registerInvokeVirtual(methodHandle.asMethod());
        break;
      case INVOKE_STATIC:
        registerInvokeStatic(methodHandle.asMethod());
        break;
      case INVOKE_CONSTRUCTOR:
        DexMethod method = methodHandle.asMethod();
        registerNewInstance(method.getHolder());
        registerInvokeDirect(method);
        break;
      case INVOKE_INTERFACE:
        registerInvokeInterface(methodHandle.asMethod());
        break;
      case INVOKE_SUPER:
        registerInvokeSuper(methodHandle.asMethod());
        break;
      case INVOKE_DIRECT:
        registerInvokeDirect(methodHandle.asMethod());
        break;
      default:
        throw new AssertionError();
    }
  }

  public void registerCallSite(DexCallSite callSite) {
    registerMethodHandle(callSite.bootstrapMethod);

    // Register bootstrap method arguments.
    // Only Type, MethodHandle, and MethodType need to be registered.
    for (DexValue arg : callSite.bootstrapArgs) {
      if (arg instanceof DexValueType) {
        registerTypeReference(((DexValueType) arg).value);
      } else if (arg instanceof DexValueMethodHandle) {
        registerMethodHandle(((DexValueMethodHandle) arg).value);
      } else if (arg instanceof DexValueMethodType) {
        registerProto(((DexValueMethodType) arg).value);
      } else {
        assert (arg instanceof DexValueInt)
            || (arg instanceof DexValueLong)
            || (arg instanceof DexValueFloat)
            || (arg instanceof DexValueDouble)
            || (arg instanceof DexValueString);
      }
    }
  }

  public void registerProto(DexProto proto) {
    registerTypeReference(proto.returnType);
    for (DexType type : proto.parameters.values) {
      registerTypeReference(type);
    }
  }
}
