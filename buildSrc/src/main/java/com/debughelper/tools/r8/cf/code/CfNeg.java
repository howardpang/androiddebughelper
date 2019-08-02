// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf.code;

import com.debughelper.tools.r8.cf.CfPrinter;
import com.debughelper.tools.r8.cf.code.CfInstruction;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.ir.conversion.CfSourceCode;
import com.debughelper.tools.r8.ir.conversion.CfState;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.ir.code.NumericType;
import com.debughelper.tools.r8.ir.code.ValueType;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfNeg extends CfInstruction {

  private final com.debughelper.tools.r8.ir.code.NumericType type;

  public CfNeg(com.debughelper.tools.r8.ir.code.NumericType type) {
    this.type = type;
  }

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    visitor.visitInsn(getAsmOpcode());
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  public int getAsmOpcode() {
    switch (type) {
      case BYTE:
      case CHAR:
      case SHORT:
      case INT:
        return Opcodes.INEG;
      case LONG:
        return Opcodes.LNEG;
      case FLOAT:
        return Opcodes.FNEG;
      case DOUBLE:
        return Opcodes.DNEG;
      default:
        throw new Unreachable("Invalid type for CfNeg " + type);
    }
  }

  public static CfNeg fromAsm(int opcode) {
    switch (opcode) {
      case Opcodes.INEG:
        return new CfNeg(com.debughelper.tools.r8.ir.code.NumericType.INT);
      case Opcodes.LNEG:
        return new CfNeg(com.debughelper.tools.r8.ir.code.NumericType.LONG);
      case Opcodes.FNEG:
        return new CfNeg(com.debughelper.tools.r8.ir.code.NumericType.FLOAT);
      case Opcodes.DNEG:
        return new CfNeg(NumericType.DOUBLE);
      default:
        throw new Unreachable("Invalid opcode for CfNeg " + opcode);
    }
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    int value = state.pop().register;
    builder.addNeg(type, state.push(ValueType.fromNumericType(type)).register, value);
  }
}
