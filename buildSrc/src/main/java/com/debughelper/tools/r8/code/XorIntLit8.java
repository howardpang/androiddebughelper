// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format22b;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.NumericType;

public class XorIntLit8 extends Format22b {

  public static final int OPCODE = 0xdf;
  public static final String NAME = "XorIntLit8";
  public static final String SMALI_NAME = "xor-int/lit8";

  XorIntLit8(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public XorIntLit8(int dest, int register, int constant) {
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
    builder.addXorLiteral(NumericType.INT, AA, BB, CC);
  }
}
