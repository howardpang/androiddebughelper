// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.ValueType;

public class StackValue extends Value {

  private final int height;
  private final DexType objectType;

  private StackValue(DexType objectType, com.debughelper.tools.r8.ir.code.ValueType valueType, int height) {
    super(Value.UNDEFINED_NUMBER, valueType, null);
    this.height = height;
    this.objectType = objectType;
    assert height >= 0;
  }

  public static StackValue forObjectType(DexType type, int height) {
    assert DexItemFactory.nullValueType == type || type.isClassType() || type.isArrayType();
    return new StackValue(type, com.debughelper.tools.r8.ir.code.ValueType.OBJECT, height);
  }

  public static StackValue forNonObjectType(ValueType valueType, int height) {
    assert valueType.isPreciseType() && !valueType.isObject();
    return new StackValue(null, valueType, height);
  }

  public int getHeight() {
    return height;
  }

  public DexType getObjectType() {
    assert outType().isObject();
    return objectType;
  }

  @Override
  public boolean needsRegister() {
    return false;
  }

  @Override
  public void setNeedsRegister(boolean value) {
    assert !value;
  }

  @Override
  public String toString() {
    return "s" + height;
  }
}
