// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.NumericType;

public class OrIntLit16 extends Format22s {

  public static final int OPCODE = 0xd6;
  public static final String NAME = "OrIntLit16";
  public static final String SMALI_NAME = "or-int/lit16";

  OrIntLit16(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public OrIntLit16(int dest, int register, int constant) {
    super(dest, register, constant);
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
    builder.addOrLiteral(NumericType.INT, A, B, CCCC);
  }
}
