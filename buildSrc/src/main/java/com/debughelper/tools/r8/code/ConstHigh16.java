// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format21h;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.SingleConstant;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.naming.ClassNameMapper;
import com.debughelper.tools.r8.utils.StringUtils;

public class ConstHigh16 extends com.debughelper.tools.r8.code.Format21h implements SingleConstant {

  public static final int OPCODE = 0x15;
  public static final String NAME = "ConstHigh16";
  public static final String SMALI_NAME = "const/high16";

  /*package*/ ConstHigh16(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public ConstHigh16(int register, int constantHighBits) {
    super(register, constantHighBits);
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
  public int decodedValue() {
    return BBBB << 16;
  }

  @Override
  public String toString(com.debughelper.tools.r8.naming.ClassNameMapper naming) {
    return formatString("v" + AA + ", " + com.debughelper.tools.r8.utils.StringUtils.hexString(decodedValue(), 8) +
        " (" + decodedValue() + ")");
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString("v" + AA + ", " + StringUtils.hexString(decodedValue(), 8) +
        "  # " + decodedValue());
  }

  @Override
  public void buildIR(IRBuilder builder) {
    int value = decodedValue();
    com.debughelper.tools.r8.ir.code.ValueType type = value == 0 ? com.debughelper.tools.r8.ir.code.ValueType.INT_OR_FLOAT_OR_NULL : ValueType.INT_OR_FLOAT;
    builder.addConst(type, AA, value);
  }
}
