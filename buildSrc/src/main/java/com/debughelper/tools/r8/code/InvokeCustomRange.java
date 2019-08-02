// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.graph.DexCallSite;
import com.debughelper.tools.r8.graph.OffsetToObjectMapping;
import com.debughelper.tools.r8.graph.UseRegistry;

public class InvokeCustomRange extends Format3rc {

  public static final int OPCODE = 0xfd;
  public static final String NAME = "InvokeCustomRange";
  public static final String SMALI_NAME = "invoke-custom/range";

  InvokeCustomRange(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getCallSiteMap());
  }

  public InvokeCustomRange(int firstArgumentRegister, int argumentCount, com.debughelper.tools.r8.graph.DexCallSite callSite) {
    super(firstArgumentRegister, argumentCount, callSite);
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
  public com.debughelper.tools.r8.graph.DexCallSite getCallSite() {
    return (DexCallSite) BBBB;
  }

  @Override
  public void registerUse(UseRegistry registry) {
    registry.registerCallSite(getCallSite());
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addInvokeCustomRange(getCallSite(), AA, CCCC);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
