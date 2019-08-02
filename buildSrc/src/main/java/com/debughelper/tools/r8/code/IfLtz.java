// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format21t;
import com.debughelper.tools.r8.ir.code.If.Type;
import com.debughelper.tools.r8.ir.code.If;
import com.debughelper.tools.r8.ir.code.ValueType;

public class IfLtz extends Format21t {

  public static final int OPCODE = 0x3a;
  public static final String NAME = "IfLtz";
  public static final String SMALI_NAME = "if-ltz";

  IfLtz(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public IfLtz(int register, int offset) {
    super(register, offset);
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
  public If.Type getType() {
    return If.Type.LT;
  }

  @Override
  protected com.debughelper.tools.r8.ir.code.ValueType getOperandType() {
    return ValueType.INT;
  }
}
