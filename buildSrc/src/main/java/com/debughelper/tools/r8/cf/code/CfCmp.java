// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf.code;

import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.ir.code.Cmp;
import com.debughelper.tools.r8.ir.code.Cmp.Bias;
import com.debughelper.tools.r8.ir.conversion.CfSourceCode;
import com.debughelper.tools.r8.ir.conversion.CfState;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.cf.CfPrinter;
import com.debughelper.tools.r8.ir.code.NumericType;
import com.debughelper.tools.r8.ir.code.ValueType;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfCmp extends CfInstruction {

  private final com.debughelper.tools.r8.ir.code.Cmp.Bias bias;
  private final com.debughelper.tools.r8.ir.code.NumericType type;

  public CfCmp(com.debughelper.tools.r8.ir.code.Cmp.Bias bias, com.debughelper.tools.r8.ir.code.NumericType type) {
    assert bias != null;
    assert type != null;
    assert type == com.debughelper.tools.r8.ir.code.NumericType.LONG || type == com.debughelper.tools.r8.ir.code.NumericType.FLOAT || type == com.debughelper.tools.r8.ir.code.NumericType.DOUBLE;
    assert type != com.debughelper.tools.r8.ir.code.NumericType.LONG || bias == com.debughelper.tools.r8.ir.code.Cmp.Bias.NONE;
    assert type == com.debughelper.tools.r8.ir.code.NumericType.LONG || bias != com.debughelper.tools.r8.ir.code.Cmp.Bias.NONE;
    this.bias = bias;
    this.type = type;
  }

  public static CfCmp fromAsm(int opcode) {
    switch (opcode) {
      case Opcodes.LCMP:
        return new CfCmp(com.debughelper.tools.r8.ir.code.Cmp.Bias.NONE, com.debughelper.tools.r8.ir.code.NumericType.LONG);
      case Opcodes.FCMPL:
        return new CfCmp(com.debughelper.tools.r8.ir.code.Cmp.Bias.LT, com.debughelper.tools.r8.ir.code.NumericType.FLOAT);
      case Opcodes.FCMPG:
        return new CfCmp(com.debughelper.tools.r8.ir.code.Cmp.Bias.GT, com.debughelper.tools.r8.ir.code.NumericType.FLOAT);
      case Opcodes.DCMPL:
        return new CfCmp(com.debughelper.tools.r8.ir.code.Cmp.Bias.LT, com.debughelper.tools.r8.ir.code.NumericType.DOUBLE);
      case Opcodes.DCMPG:
        return new CfCmp(com.debughelper.tools.r8.ir.code.Cmp.Bias.GT, NumericType.DOUBLE);
      default:
        throw new Unreachable("Wrong ASM opcode for CfCmp " + opcode);
    }
  }

  public int getAsmOpcode() {
    switch (type) {
      case LONG:
        return Opcodes.LCMP;
      case FLOAT:
        return bias == com.debughelper.tools.r8.ir.code.Cmp.Bias.LT ? Opcodes.FCMPL : Opcodes.FCMPG;
      case DOUBLE:
        return bias == com.debughelper.tools.r8.ir.code.Cmp.Bias.LT ? Opcodes.DCMPL : Opcodes.DCMPG;
      default:
        throw new Unreachable("CfCmp has unknown type " + type);
    }
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    visitor.visitInsn(getAsmOpcode());
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    int right = state.pop().register;
    int left = state.pop().register;
    builder.addCmp(type, bias, state.push(ValueType.fromNumericType(type)).register, left, right);
  }
}
