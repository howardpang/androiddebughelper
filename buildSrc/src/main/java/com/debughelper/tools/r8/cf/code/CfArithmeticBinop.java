// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf.code;

import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.ir.conversion.CfSourceCode;
import com.debughelper.tools.r8.ir.conversion.CfState;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.cf.CfPrinter;
import com.debughelper.tools.r8.ir.code.NumericType;
import com.debughelper.tools.r8.ir.code.ValueType;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfArithmeticBinop extends CfInstruction {

  public enum Opcode {
    Add,
    Sub,
    Mul,
    Div,
    Rem,
  }

  private final Opcode opcode;
  private final com.debughelper.tools.r8.ir.code.NumericType type;

  public CfArithmeticBinop(Opcode opcode, com.debughelper.tools.r8.ir.code.NumericType type) {
    assert opcode != null;
    assert type != null;
    this.opcode = opcode;
    this.type = type;
  }

  public static CfArithmeticBinop fromAsm(int opcode) {
    switch (opcode) {
      case Opcodes.IADD:
        return new CfArithmeticBinop(Opcode.Add, com.debughelper.tools.r8.ir.code.NumericType.INT);
      case Opcodes.LADD:
        return new CfArithmeticBinop(Opcode.Add, com.debughelper.tools.r8.ir.code.NumericType.LONG);
      case Opcodes.FADD:
        return new CfArithmeticBinop(Opcode.Add, com.debughelper.tools.r8.ir.code.NumericType.FLOAT);
      case Opcodes.DADD:
        return new CfArithmeticBinop(Opcode.Add, com.debughelper.tools.r8.ir.code.NumericType.DOUBLE);
      case Opcodes.ISUB:
        return new CfArithmeticBinop(Opcode.Sub, com.debughelper.tools.r8.ir.code.NumericType.INT);
      case Opcodes.LSUB:
        return new CfArithmeticBinop(Opcode.Sub, com.debughelper.tools.r8.ir.code.NumericType.LONG);
      case Opcodes.FSUB:
        return new CfArithmeticBinop(Opcode.Sub, com.debughelper.tools.r8.ir.code.NumericType.FLOAT);
      case Opcodes.DSUB:
        return new CfArithmeticBinop(Opcode.Sub, com.debughelper.tools.r8.ir.code.NumericType.DOUBLE);
      case Opcodes.IMUL:
        return new CfArithmeticBinop(Opcode.Mul, com.debughelper.tools.r8.ir.code.NumericType.INT);
      case Opcodes.LMUL:
        return new CfArithmeticBinop(Opcode.Mul, com.debughelper.tools.r8.ir.code.NumericType.LONG);
      case Opcodes.FMUL:
        return new CfArithmeticBinop(Opcode.Mul, com.debughelper.tools.r8.ir.code.NumericType.FLOAT);
      case Opcodes.DMUL:
        return new CfArithmeticBinop(Opcode.Mul, com.debughelper.tools.r8.ir.code.NumericType.DOUBLE);
      case Opcodes.IDIV:
        return new CfArithmeticBinop(Opcode.Div, com.debughelper.tools.r8.ir.code.NumericType.INT);
      case Opcodes.LDIV:
        return new CfArithmeticBinop(Opcode.Div, com.debughelper.tools.r8.ir.code.NumericType.LONG);
      case Opcodes.FDIV:
        return new CfArithmeticBinop(Opcode.Div, com.debughelper.tools.r8.ir.code.NumericType.FLOAT);
      case Opcodes.DDIV:
        return new CfArithmeticBinop(Opcode.Div, com.debughelper.tools.r8.ir.code.NumericType.DOUBLE);
      case Opcodes.IREM:
        return new CfArithmeticBinop(Opcode.Rem, com.debughelper.tools.r8.ir.code.NumericType.INT);
      case Opcodes.LREM:
        return new CfArithmeticBinop(Opcode.Rem, com.debughelper.tools.r8.ir.code.NumericType.LONG);
      case Opcodes.FREM:
        return new CfArithmeticBinop(Opcode.Rem, com.debughelper.tools.r8.ir.code.NumericType.FLOAT);
      case Opcodes.DREM:
        return new CfArithmeticBinop(Opcode.Rem, com.debughelper.tools.r8.ir.code.NumericType.DOUBLE);
      default:
        throw new Unreachable("Wrong ASM opcode for CfArithmeticBinop " + opcode);
    }
  }

  public int getAsmOpcode() {
    switch (opcode) {
      case Add:
        return Opcodes.IADD + getAsmOpcodeTypeOffset();
      case Sub:
        return Opcodes.ISUB + getAsmOpcodeTypeOffset();
      case Mul:
        return Opcodes.IMUL + getAsmOpcodeTypeOffset();
      case Div:
        return Opcodes.IDIV + getAsmOpcodeTypeOffset();
      case Rem:
        return Opcodes.IREM + getAsmOpcodeTypeOffset();
      default:
        throw new Unreachable("CfArithmeticBinop has unknown opcode " + opcode);
    }
  }

  private int getAsmOpcodeTypeOffset() {
    switch (type) {
      case LONG:
        return Opcodes.LADD - Opcodes.IADD;
      case FLOAT:
        return Opcodes.FADD - Opcodes.IADD;
      case DOUBLE:
        return Opcodes.DADD - Opcodes.IADD;
      default:
        return 0;
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
  public boolean canThrow() {
    return (type != com.debughelper.tools.r8.ir.code.NumericType.FLOAT && type != NumericType.DOUBLE)
        && (opcode == Opcode.Div || opcode == Opcode.Rem);
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    int right = state.pop().register;
    int left = state.pop().register;
    int dest = state.push(ValueType.fromNumericType(type)).register;
    switch (opcode) {
      case Add:
        builder.addAdd(type, dest, left, right);
        break;
      case Sub:
        builder.addSub(type, dest, left, right);
        break;
      case Mul:
        builder.addMul(type, dest, left, right);
        break;
      case Div:
        builder.addDiv(type, dest, left, right);
        break;
      case Rem:
        builder.addRem(type, dest, left, right);
        break;
      default:
        throw new Unreachable("CfArithmeticBinop has unknown opcode " + opcode);
    }
  }
}
