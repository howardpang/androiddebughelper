// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format21c;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.OffsetToObjectMapping;
import com.debughelper.tools.r8.graph.UseRegistry;

public class NewInstance extends com.debughelper.tools.r8.code.Format21c {

  public static final int OPCODE = 0x22;
  public static final String NAME = "NewInstance";
  public static final String SMALI_NAME = "new-instance";

  NewInstance(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getTypeMap());
  }

  public NewInstance(int AA, com.debughelper.tools.r8.graph.DexType BBBB) {
    super(AA, BBBB);
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
  public void registerUse(UseRegistry registry) {
    registry.registerNewInstance(getType());
  }

  public com.debughelper.tools.r8.graph.DexType getType() {
    return (DexType) BBBB;
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addNewInstance(AA, getType());
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
