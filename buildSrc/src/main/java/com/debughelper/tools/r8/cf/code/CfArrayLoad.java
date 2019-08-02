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
import com.debughelper.tools.r8.ir.code.MemberType;
import com.debughelper.tools.r8.ir.code.ValueType;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfArrayLoad extends CfInstruction {

  private final com.debughelper.tools.r8.ir.code.MemberType type;

  public CfArrayLoad(com.debughelper.tools.r8.ir.code.MemberType type) {
    this.type = type;
  }

  public MemberType getType() {
    return type;
  }

  private int getLoadType() {
    switch (type) {
      case OBJECT:
        return Opcodes.AALOAD;
      case BYTE:
      case BOOLEAN:
        return Opcodes.BALOAD;
      case CHAR:
        return Opcodes.CALOAD;
      case SHORT:
        return Opcodes.SALOAD;
      case INT:
        return Opcodes.IALOAD;
      case FLOAT:
        return Opcodes.FALOAD;
      case LONG:
        return Opcodes.LALOAD;
      case DOUBLE:
        return Opcodes.DALOAD;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
  }

  @Override
  public void write(MethodVisitor visitor, NamingLens lens) {
    visitor.visitInsn(getLoadType());
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  @Override
  public boolean canThrow() {
    return true;
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    Slot index = state.pop();
    Slot array = state.pop();
    Slot value;
    assert array.type.isObject();
    com.debughelper.tools.r8.ir.code.ValueType memberType = ValueType.fromMemberType(type);
    if (array.preciseType != null) {
      value = state.push(array.preciseType.toArrayElementType(builder.getFactory()));
      assert state.peek().type == memberType;
    } else {
      value = state.push(memberType);
    }
    builder.addArrayGet(type, value.register, array.register, index.register);
  }
}
