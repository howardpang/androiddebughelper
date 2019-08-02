// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.UseRegistry;

public class DefaultUseRegistry extends UseRegistry {

  @Override
  public boolean registerInvokeVirtual(com.debughelper.tools.r8.graph.DexMethod method) {
    return true;
  }

  @Override
  public boolean registerInvokeDirect(com.debughelper.tools.r8.graph.DexMethod method) {
    return true;
  }

  @Override
  public boolean registerInvokeStatic(com.debughelper.tools.r8.graph.DexMethod method) {
    return true;
  }

  @Override
  public boolean registerInvokeInterface(com.debughelper.tools.r8.graph.DexMethod method) {
    return true;
  }

  @Override
  public boolean registerInvokeSuper(DexMethod method) {
    return true;
  }

  @Override
  public boolean registerInstanceFieldWrite(com.debughelper.tools.r8.graph.DexField field) {
    return true;
  }

  @Override
  public boolean registerInstanceFieldRead(com.debughelper.tools.r8.graph.DexField field) {
    return true;
  }

  @Override
  public boolean registerNewInstance(com.debughelper.tools.r8.graph.DexType type) {
    return true;
  }

  @Override
  public boolean registerStaticFieldRead(com.debughelper.tools.r8.graph.DexField field) {
    return true;
  }

  @Override
  public boolean registerStaticFieldWrite(DexField field) {
    return true;
  }

  @Override
  public boolean registerTypeReference(DexType type) {
    return true;
  }
}
