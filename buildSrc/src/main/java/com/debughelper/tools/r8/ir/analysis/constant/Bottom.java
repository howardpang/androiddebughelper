// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.analysis.constant;

import com.debughelper.tools.r8.ir.analysis.constant.LatticeElement;

public class Bottom extends com.debughelper.tools.r8.ir.analysis.constant.LatticeElement {
  private static final Bottom INSTANCE = new Bottom();

  private Bottom() {
  }

  public static Bottom getInstance() {
    return INSTANCE;
  }

  @Override
  public com.debughelper.tools.r8.ir.analysis.constant.LatticeElement meet(LatticeElement other) {
    return this;
  }

  @Override
  public boolean isBottom() {
    return true;
  }

  @Override
  public String toString() {
    return "BOTTOM";
  }
}
