// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format12x;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.NumericType;

public class DivDouble2Addr extends com.debughelper.tools.r8.code.Format12x {

  public static final int OPCODE = 0xce;
  public static final String NAME = "DivDouble2Addr";
  public static final String SMALI_NAME = "div-double/2addr";

  DivDouble2Addr(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public DivDouble2Addr(int left, int right) {
    super(left, right);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getSmaliName() {
    return SMALI_NAME;
  }

  @Override
  public int getOpcode() {
    return OPCODE;
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addDiv(NumericType.DOUBLE, A, A, B);
  }
}
