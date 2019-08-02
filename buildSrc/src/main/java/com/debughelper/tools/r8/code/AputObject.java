// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format23x;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.code.MemberType;

public class AputObject extends com.debughelper.tools.r8.code.Format23x {

  public static final int OPCODE = 0x4d;
  public static final String NAME = "AputObject";
  public static final String SMALI_NAME = "aput-object";

  /*package*/ AputObject(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public AputObject(int AA, int BB, int CC) {
    super(AA, BB, CC);
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
    builder.addArrayPut(MemberType.OBJECT, AA, BB, CC);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
