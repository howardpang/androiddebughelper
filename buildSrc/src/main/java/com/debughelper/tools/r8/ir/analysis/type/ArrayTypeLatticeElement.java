// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.analysis.type;

import com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexType;

public class ArrayTypeLatticeElement extends com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement {
  private final com.debughelper.tools.r8.graph.DexType arrayType;

  ArrayTypeLatticeElement(com.debughelper.tools.r8.graph.DexType arrayType, boolean isNullable) {
    super(isNullable);
    this.arrayType = arrayType;
  }

  public com.debughelper.tools.r8.graph.DexType getArrayType() {
    return arrayType;
  }

  public int getNesting() {
    return arrayType.getNumberOfLeadingSquareBrackets();
  }

  public com.debughelper.tools.r8.graph.DexType getArrayElementType(com.debughelper.tools.r8.graph.DexItemFactory factory) {
    return arrayType.toArrayElementType(factory);
  }

  public DexType getArrayBaseType(DexItemFactory factory) {
    return arrayType.toBaseType(factory);
  }

  @Override
  com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement asNullable() {
    return isNullable() ? this : new ArrayTypeLatticeElement(arrayType, true);
  }

  @Override
  public com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement asNonNullable() {
    return isNullable() ? new ArrayTypeLatticeElement(arrayType, false) : this;
  }

  @Override
  public boolean isArrayTypeLatticeElement() {
    return true;
  }

  @Override
  public ArrayTypeLatticeElement asArrayTypeLatticeElement() {
    return this;
  }

  @Override
  public TypeLatticeElement arrayGet(AppInfo appInfo) {
    return fromDexType(getArrayElementType(appInfo.dexItemFactory), true);
  }

  @Override
  public String toString() {
    return isNullableString() + arrayType.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (!super.equals(o)) {
      return false;
    }
    ArrayTypeLatticeElement other = (ArrayTypeLatticeElement) o;
    return arrayType == other.arrayType;
  }

  @Override
  public int hashCode() {
    int result = super.hashCode();
    result = 31 * result + arrayType.hashCode();
    return result;
  }
}
