// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format35c;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.OffsetToObjectMapping;

public class FilledNewArray extends Format35c {

  public static final int OPCODE = 0x24;
  public static final String NAME = "FilledNewArray";
  public static final String SMALI_NAME = "filled-new-array";

  FilledNewArray(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getTypeMap());
  }

  public FilledNewArray(int size, com.debughelper.tools.r8.graph.DexType type, int v0, int v1, int v2, int v3, int v4) {
    super(size, type, v0, v1, v2, v3, v4);
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

  public com.debughelper.tools.r8.graph.DexType getType() {
    return (DexType) BBBB;
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addInvokeNewArray(getType(), A, new int[]{C, D, E, F, G});
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
