// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format21c;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.OffsetToObjectMapping;
import com.debughelper.tools.r8.graph.UseRegistry;

public class SgetBoolean extends com.debughelper.tools.r8.code.Format21c {

  public static final int OPCODE = 0x63;
  public static final String NAME = "SgetBoolean";
  public static final String SMALI_NAME = "sget-boolean";

  SgetBoolean(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getFieldMap());
  }

  public SgetBoolean(int AA, com.debughelper.tools.r8.graph.DexField BBBB) {
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
    registry.registerStaticFieldRead(getField());
  }

  @Override
  public com.debughelper.tools.r8.graph.DexField getField() {
    return (DexField) BBBB;
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addStaticGet(AA, getField());
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
