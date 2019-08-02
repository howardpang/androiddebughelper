// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf.code;

import com.debughelper.tools.r8.cf.CfPrinter;
import com.debughelper.tools.r8.cf.code.CfInstruction;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.ir.conversion.CfSourceCode;
import com.debughelper.tools.r8.ir.conversion.CfState;
import com.debughelper.tools.r8.ir.conversion.CfState.Slot;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.ir.code.ValueType;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfReturn extends CfInstruction {

  private final com.debughelper.tools.r8.ir.code.ValueType type;

  public CfReturn(com.debughelper.tools.r8.ir.code.ValueType type) {
    this.type = type;
  }

  public ValueType getType() {
    return type;
  }

  private int getOpcode() {
    switch (type) {
      case INT:
        return Opcodes.IRETURN;
      case FLOAT:
        return Opcodes.FRETURN;
      case LONG:
        return Opcodes.LRETURN;
      case DOUBLE:
        return Opcodes.DRETURN;
      case OBJECT:
        return Opcodes.ARETURN;
      default:
        throw new Unreachable("Unexpected return type: " + type);
    }
  }

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    visitor.visitInsn(getOpcode());
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  @Override
  public boolean isReturn() {
    return true;
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    Slot pop = state.pop();
    builder.addReturn(pop.type, pop.register);
  }
}
