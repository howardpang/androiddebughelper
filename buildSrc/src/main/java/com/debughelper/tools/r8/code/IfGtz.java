// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format21t;
import com.debughelper.tools.r8.ir.code.If.Type;
import com.debughelper.tools.r8.ir.code.If;
import com.debughelper.tools.r8.ir.code.ValueType;

public class IfGtz extends Format21t {

  public static final int OPCODE = 0x3c;
  public static final String NAME = "IfGtz";
  public static final String SMALI_NAME = "if-gtz";

  IfGtz(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public IfGtz(int register, int offset) {
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
    return If.Type.GT;
  }

  @Override
  protected com.debughelper.tools.r8.ir.code.ValueType getOperandType() {
    return ValueType.INT;
  }
}
