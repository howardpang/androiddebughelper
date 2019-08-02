// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format12x;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;

public class ArrayLength extends com.debughelper.tools.r8.code.Format12x {

  public static final int OPCODE = 0x21;
  public static final String NAME = "ArrayLength";
  public static final String SMALI_NAME = "array-length";

  ArrayLength(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public ArrayLength(int dest, int array) {
    super(dest, array);
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
    builder.addArrayLength(A, B);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
