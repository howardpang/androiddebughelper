// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format12x;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.NumericType;

public class DivLong2Addr extends com.debughelper.tools.r8.code.Format12x {

  public static final int OPCODE = 0xbe;
  public static final String NAME = "DivLong2Addr";
  public static final String SMALI_NAME = "div-long/2addr";

  DivLong2Addr(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public DivLong2Addr(int left, int right) {
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
    builder.addDiv(NumericType.LONG, A, A, B);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
