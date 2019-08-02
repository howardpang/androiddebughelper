// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.graph.DexCallSite;
import com.debughelper.tools.r8.graph.IndexedDexItem;
import com.debughelper.tools.r8.graph.OffsetToObjectMapping;
import com.debughelper.tools.r8.graph.UseRegistry;

public class InvokeCustom extends Format35c {

  public static final int OPCODE = 0xfc;
  public static final String NAME = "InvokeCustom";
  public static final String SMALI_NAME = "invoke-custom";

  InvokeCustom(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getCallSiteMap());
  }

  public InvokeCustom(int A, IndexedDexItem BBBB, int C, int D, int E, int F, int G) {
    super(A, BBBB, C, D, E, F, G);
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
    registry.registerCallSite(getCallSite());
  }

  @Override
  public com.debughelper.tools.r8.graph.DexCallSite getCallSite() {
    return (DexCallSite) BBBB;
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addInvokeCustomRegisters(getCallSite(), A, new int[]{C, D, E, F, G});
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
