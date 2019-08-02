// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format22c;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.OffsetToObjectMapping;
import com.debughelper.tools.r8.graph.UseRegistry;

public class NewArray extends Format22c {

  public static final int OPCODE = 0x23;
  public static final String NAME = "NewArray";
  public static final String SMALI_NAME = "new-array";

  /*package*/ NewArray(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getTypeMap());
  }

  public NewArray(int dest, int size, com.debughelper.tools.r8.graph.DexType type) {
    super(dest, size, type);
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
    registry.registerTypeReference(getType());
  }

  public com.debughelper.tools.r8.graph.DexType getType() {
    return (DexType) CCCC;
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addNewArrayEmpty(A, B, getType());
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
