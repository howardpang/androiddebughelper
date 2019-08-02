// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.analysis.constant;

import com.debughelper.tools.r8.ir.analysis.constant.Bottom;
import com.debughelper.tools.r8.ir.analysis.constant.LatticeElement;
import com.debughelper.tools.r8.ir.code.Value;

public class ConstRangeLatticeElement extends com.debughelper.tools.r8.ir.analysis.constant.LatticeElement {
  private final com.debughelper.tools.r8.ir.code.Value value;

  public ConstRangeLatticeElement(com.debughelper.tools.r8.ir.code.Value value) {
    assert value.hasValueRange();
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
    if (other.isValueRange()) {
      ConstRangeLatticeElement otherRange = other.asConstRange();
      if (getConstRange().getValueRange().equals(otherRange.getConstRange().getValueRange())) {
        return this;
      }
    }
    return Bottom.getInstance();
  }

  @Override
  public boolean isValueRange() {
    return true;
  }

  @Override
  public String toString() {
    return value.toString();
  }

  public Value getConstRange() {
    return value;
  }

  @Override
  public ConstRangeLatticeElement asConstRange() {
    return this;
  }
}
