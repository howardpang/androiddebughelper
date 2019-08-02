// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf.code;

import com.debughelper.tools.r8.cf.CfPrinter;
import com.debughelper.tools.r8.cf.code.CfInstruction;
import com.debughelper.tools.r8.cf.code.CfLabel;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.ir.code.If;
import com.debughelper.tools.r8.ir.code.If.Type;
import com.debughelper.tools.r8.ir.conversion.CfSourceCode;
import com.debughelper.tools.r8.ir.conversion.CfState;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.ir.code.ValueType;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfIf extends CfInstruction {

  private final com.debughelper.tools.r8.ir.code.If.Type kind;
  private final com.debughelper.tools.r8.ir.code.ValueType type;
  private final com.debughelper.tools.r8.cf.code.CfLabel target;

  public CfIf(com.debughelper.tools.r8.ir.code.If.Type kind, com.debughelper.tools.r8.ir.code.ValueType type, com.debughelper.tools.r8.cf.code.CfLabel target) {
    this.kind = kind;
    this.type = type;
    this.target = target;
  }

  public ValueType getType() {
    return type;
  }

  public com.debughelper.tools.r8.ir.code.If.Type getKind() {
    return kind;
  }

  @Override
  public CfLabel getTarget() {
    return target;
  }

  public int getOpcode() {
    switch (kind) {
      case EQ:
        return type.isObject() ? Opcodes.IFNULL : Opcodes.IFEQ;
      case GE:
        return Opcodes.IFGE;
      case GT:
        return Opcodes.IFGT;
      case LE:
        return Opcodes.IFLE;
      case LT:
        return Opcodes.IFLT;
      case NE:
        return type.isObject() ? Opcodes.IFNONNULL : Opcodes.IFNE;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    visitor.visitJumpInsn(getOpcode(), target.getLabel());
  }

  @Override
  public boolean isConditionalJump() {
    return true;
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    int value = state.pop().register;
    int trueTargetOffset = code.getLabelOffset(target);
    int falseTargetOffset = code.getCurrentInstructionIndex() + 1;
    builder.addIfZero(kind, type, value, trueTargetOffset, falseTargetOffset);
  }
}
