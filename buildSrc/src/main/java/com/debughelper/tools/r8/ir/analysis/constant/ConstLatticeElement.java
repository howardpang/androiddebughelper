// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.analysis.constant;

import com.debughelper.tools.r8.ir.analysis.constant.Bottom;
import com.debughelper.tools.r8.ir.analysis.constant.LatticeElement;
import com.debughelper.tools.r8.ir.code.ConstNumber;

public class ConstLatticeElement extends com.debughelper.tools.r8.ir.analysis.constant.LatticeElement {
  private final com.debughelper.tools.r8.ir.code.ConstNumber value;

  public ConstLatticeElement(com.debughelper.tools.r8.ir.code.ConstNumber value) {
    this.value = value;
  }

  @Override
  public com.debughelper.tools.r8.ir.analysis.constant.LatticeElement meet(LatticeElement other) {
    if (other.isTop()) {
      return this;
    }
    if (other.isBottom()) {
      return other;
    }
    if (value.identicalNonValueNonPositionParts(other.asConst().value)) {
      return this;
    }
    return Bottom.getInstance();
  }

  @Override
  public boolean isConst() {
    return true;
  }

  @Override
  public ConstLatticeElement asConst() {
    return this;
  }

  @Override
  public String toString() {
    return value.toString();
  }

  public ConstNumber getConstNumber() {
    return value;
  }

  public int getIntValue() {
    return value.getIntValue();
  }

  public long getLongValue() {
    return value.getLongValue();
  }

  public float getFloatValue() {
    return value.getFloatValue();
  }

  public double getDoubleValue() {
    return value.getDoubleValue();
  }
}
