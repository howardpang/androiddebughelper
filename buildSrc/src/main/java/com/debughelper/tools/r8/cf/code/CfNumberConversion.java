// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
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

public class CfNumberConversion extends CfInstruction {

  private final com.debughelper.tools.r8.ir.code.NumericType from;
  private final com.debughelper.tools.r8.ir.code.NumericType to;

  public CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType from, com.debughelper.tools.r8.ir.code.NumericType to) {
    assert from != to;
    assert from != com.debughelper.tools.r8.ir.code.NumericType.BYTE && from != com.debughelper.tools.r8.ir.code.NumericType.SHORT && from != com.debughelper.tools.r8.ir.code.NumericType.CHAR;
    assert (to != com.debughelper.tools.r8.ir.code.NumericType.BYTE && to != com.debughelper.tools.r8.ir.code.NumericType.SHORT && to != com.debughelper.tools.r8.ir.code.NumericType.CHAR)
        || from == com.debughelper.tools.r8.ir.code.NumericType.INT;
    this.from = from;
    this.to = to;
  }

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    visitor.visitInsn(this.getAsmOpcode());
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  public int getAsmOpcode() {
    switch (from) {
      case INT:
        switch (to) {
          case BYTE:
            return Opcodes.I2B;
          case CHAR:
            return Opcodes.I2C;
          case SHORT:
            return Opcodes.I2S;
          case LONG:
            return Opcodes.I2L;
          case FLOAT:
            return Opcodes.I2F;
          case DOUBLE:
            return Opcodes.I2D;
          default:
            throw new Unreachable("Invalid CfNumberConversion from " + from + " to " + to);
        }
      case LONG:
        switch (to) {
          case INT:
            return Opcodes.L2I;
          case FLOAT:
            return Opcodes.L2F;
          case DOUBLE:
            return Opcodes.L2D;
          default:
            throw new Unreachable("Invalid CfNumberConversion from " + from + " to " + to);
        }
      case FLOAT:
        switch (to) {
          case INT:
            return Opcodes.F2I;
          case LONG:
            return Opcodes.F2L;
          case DOUBLE:
            return Opcodes.F2D;
          default:
            throw new Unreachable("Invalid CfNumberConversion from " + from + " to " + to);
        }
      case DOUBLE:
        switch (to) {
          case INT:
            return Opcodes.D2I;
          case LONG:
            return Opcodes.D2L;
          case FLOAT:
            return Opcodes.D2F;
          default:
            throw new Unreachable("Invalid CfNumberConversion from " + from + " to " + to);
        }
      default:
        throw new Unreachable("Invalid CfNumberConversion from " + from + " to " + to);
    }
  }

  public static CfNumberConversion fromAsm(int opcode) {
    switch (opcode) {
      case Opcodes.I2L:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.INT, com.debughelper.tools.r8.ir.code.NumericType.LONG);
      case Opcodes.I2F:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.INT, com.debughelper.tools.r8.ir.code.NumericType.FLOAT);
      case Opcodes.I2D:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.INT, com.debughelper.tools.r8.ir.code.NumericType.DOUBLE);
      case Opcodes.L2I:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.LONG, com.debughelper.tools.r8.ir.code.NumericType.INT);
      case Opcodes.L2F:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.LONG, com.debughelper.tools.r8.ir.code.NumericType.FLOAT);
      case Opcodes.L2D:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.LONG, com.debughelper.tools.r8.ir.code.NumericType.DOUBLE);
      case Opcodes.F2I:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.FLOAT, com.debughelper.tools.r8.ir.code.NumericType.INT);
      case Opcodes.F2L:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.FLOAT, com.debughelper.tools.r8.ir.code.NumericType.LONG);
      case Opcodes.F2D:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.FLOAT, com.debughelper.tools.r8.ir.code.NumericType.DOUBLE);
      case Opcodes.D2I:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.DOUBLE, com.debughelper.tools.r8.ir.code.NumericType.INT);
      case Opcodes.D2L:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.DOUBLE, com.debughelper.tools.r8.ir.code.NumericType.LONG);
      case Opcodes.D2F:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.DOUBLE, com.debughelper.tools.r8.ir.code.NumericType.FLOAT);
      case Opcodes.I2B:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.INT, com.debughelper.tools.r8.ir.code.NumericType.BYTE);
      case Opcodes.I2C:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.INT, com.debughelper.tools.r8.ir.code.NumericType.CHAR);
      case Opcodes.I2S:
        return new CfNumberConversion(com.debughelper.tools.r8.ir.code.NumericType.INT, NumericType.SHORT);
      default:
        throw new Unreachable("Unexpected CfNumberConversion opcode " + opcode);
    }
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    int source = state.pop().register;
    builder.addConversion(to, from, state.push(ValueType.fromNumericType(to)).register, source);
  }
}
