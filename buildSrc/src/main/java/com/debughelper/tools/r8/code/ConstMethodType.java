// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.code;

import com.debughelper.tools.r8.code.BytecodeStream;
import com.debughelper.tools.r8.code.Format21c;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.errors.InternalCompilerError;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.ObjectToOffsetMapping;
import com.debughelper.tools.r8.graph.OffsetToObjectMapping;
import com.debughelper.tools.r8.graph.UseRegistry;
import com.debughelper.tools.r8.naming.ClassNameMapper;

import java.nio.ShortBuffer;

public class ConstMethodType extends com.debughelper.tools.r8.code.Format21c {

  public static final int OPCODE = 0xff;
  public static final String NAME = "ConstMethodType";
  public static final String SMALI_NAME = "const-method-type";

  ConstMethodType(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getProtosMap());
  }

  public ConstMethodType(int register, com.debughelper.tools.r8.graph.DexProto methodType) {
    super(register, methodType);
  }

  public com.debughelper.tools.r8.graph.DexProto getMethodType() {
    return (com.debughelper.tools.r8.graph.DexProto) BBBB;
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
  public String toString(com.debughelper.tools.r8.naming.ClassNameMapper naming) {
    return formatString("v" + AA + ", \"" + BBBB.toString() + "\"");
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString("v" + AA + ", \"" + BBBB.toString() + "\"");
  }

  @Override
  public void registerUse(UseRegistry registry) {
    registry.registerProto(getMethodType());
  }

  @Override
  public void write(ShortBuffer dest, ObjectToOffsetMapping mapping) {
    int index = BBBB.getOffset(mapping);
    if (index != (index & 0xffff)) {
      throw new InternalCompilerError("MethodType-index overflow.");
    }
    super.write(dest, mapping);
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addConstMethodType(AA, (DexProto) BBBB);
  }

  @Override
  public boolean canThrow() {
    return true;
  }
}
