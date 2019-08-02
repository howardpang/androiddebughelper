// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.SingleConstant;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.naming.ClassNameMapper;
import com.debughelper.tools.r8.utils.StringUtils;

public class Const16 extends Format21s implements SingleConstant {

  public static final int OPCODE = 0x13;
  public static final String NAME = "Const16";
  public static final String SMALI_NAME = "const/16";

  /*package*/ Const16(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public Const16(int dest, int constant) {
    super(dest, constant);
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
    return BBBB;
  }

  @Override
  public String toString(ClassNameMapper naming) {
    return formatString("v" + AA + ", " + StringUtils.hexString(decodedValue(), 4) +
        " (" + decodedValue() + ")");
  }

  @Override
  public void buildIR(IRBuilder builder) {
    int value = decodedValue();
    com.debughelper.tools.r8.ir.code.ValueType type = value == 0 ? com.debughelper.tools.r8.ir.code.ValueType.INT_OR_FLOAT_OR_NULL : ValueType.INT_OR_FLOAT;
    builder.addConst(type, AA, value);
  }
}
