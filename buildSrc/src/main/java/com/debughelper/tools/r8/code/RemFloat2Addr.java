// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format12x;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.NumericType;

public class RemFloat2Addr extends com.debughelper.tools.r8.code.Format12x {

  public static final int OPCODE = 0xca;
  public static final String NAME = "RemFloat2Addr";
  public static final String SMALI_NAME = "rem-float/2addr";

  RemFloat2Addr(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public RemFloat2Addr(int left, int right) {
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
    builder.addRem(NumericType.FLOAT, A, A, B);
  }
}
