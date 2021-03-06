// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format23x;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.NumericType;

public class MulFloat extends com.debughelper.tools.r8.code.Format23x {

  public static final int OPCODE = 0xa8;
  public static final String NAME = "MulFloat";
  public static final String SMALI_NAME = "mul-float";

  MulFloat(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public MulFloat(int dest, int left, int right) {
    super(dest, left, right);
    // The art x86 backend had a bug that made it fail on "mul r0, r1, r0" instructions where
    // the second src register and the dst register is the same (but the first src register is
    // different). Therefore, we have to avoid generating that pattern. The bug was fixed for
    // debughelper M: https://debughelper-review.googlesource.com/#/c/114932/
    assert dest != right || dest == left;
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
    builder.addMul(NumericType.FLOAT, AA, BB, CC);
  }
}
