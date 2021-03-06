// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;

public class MoveResultWide extends Format11x {

  public static final int OPCODE = 0xb;
  public static final String NAME = "MoveResultWide";
  public static final String SMALI_NAME = "move-result-wide";

  /*package*/ MoveResultWide(int high, BytecodeStream stream) {
    super(high, stream);
  }

  public MoveResultWide(int AA) {
    super(AA);
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
    builder.addMoveResult(AA);
  }
}
