// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.ir.code.Invoke.Type;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.OffsetToObjectMapping;
import com.debughelper.tools.r8.graph.UseRegistry;
import com.debughelper.tools.r8.ir.code.Invoke;

public class InvokeSuperRange extends Format3rc {

  public static final int OPCODE = 0x75;
  public static final String NAME = "InvokeSuperRange";
  public static final String SMALI_NAME = "invoke-super/range";

  InvokeSuperRange(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getMethodMap());
  }

  public InvokeSuperRange(int firstArgumentRegister, int argumentCount, com.debughelper.tools.r8.graph.DexMethod method) {
    super(firstArgumentRegister, argumentCount, method);
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
    registry.registerInvokeSuper(getMethod());
  }

  @Override
  public com.debughelper.tools.r8.graph.DexMethod getMethod() {
    return (DexMethod) BBBB;
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addInvokeRange(Invoke.Type.SUPER, getMethod(), getProto(), AA, CCCC);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
