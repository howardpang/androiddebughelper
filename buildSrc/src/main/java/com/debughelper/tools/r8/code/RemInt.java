// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format23x;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.NumericType;

public class RemInt extends com.debughelper.tools.r8.code.Format23x {

  public static final int OPCODE = 0x94;
  public static final String NAME = "RemInt";
  public static final String SMALI_NAME = "rem-int";

  RemInt(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public RemInt(int dest, int left, int right) {
    super(dest, left, right);
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
    builder.addRem(NumericType.INT, AA, BB, CC);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
