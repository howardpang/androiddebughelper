// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.NumericType;

public class FloatToDouble extends Format12x {

  public static final int OPCODE = 0x89;
  public static final String NAME = "FloatToDouble";
  public static final String SMALI_NAME = "float-to-double";

  FloatToDouble(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public FloatToDouble(int dest, int source) {
    super(dest, source);
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
    builder.addConversion(com.debughelper.tools.r8.ir.code.NumericType.DOUBLE, NumericType.FLOAT, A, B);
  }
}
