// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.cf.code.CfConstNumber;
import com.debughelper.tools.r8.cf.code.CfLogicalBinop;
import com.debughelper.tools.r8.code.NotInt;
import com.debughelper.tools.r8.code.NotLong;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.ir.analysis.constant.Bottom;
import com.debughelper.tools.r8.ir.analysis.constant.ConstLatticeElement;
import com.debughelper.tools.r8.ir.analysis.constant.LatticeElement;
import com.debughelper.tools.r8.ir.code.ConstNumber;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.NumericType;
import com.debughelper.tools.r8.ir.code.Unop;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import java.util.function.Function;

public class Not extends Unop {

  public final com.debughelper.tools.r8.ir.code.NumericType type;

  public Not(com.debughelper.tools.r8.ir.code.NumericType type, Value dest, Value source) {
    super(dest, source);
    this.type = type;
  }

  @Override
  public boolean canBeFolded() {
    return source().isConstant();
  }

  @Override
  public LatticeElement evaluate(IRCode code, Function<Value, LatticeElement> getLatticeElement) {
    LatticeElement sourceLattice = getLatticeElement.apply(source());
    if (sourceLattice.isConst()) {
      com.debughelper.tools.r8.ir.code.ConstNumber sourceConst = sourceLattice.asConst().getConstNumber();
      com.debughelper.tools.r8.ir.code.ValueType valueType = com.debughelper.tools.r8.ir.code.ValueType.fromNumericType(type);
      Value value = code.createValue(valueType, getLocalInfo());
      com.debughelper.tools.r8.ir.code.ConstNumber newConst;
      if (type == com.debughelper.tools.r8.ir.code.NumericType.INT) {
        newConst = new com.debughelper.tools.r8.ir.code.ConstNumber(value, ~sourceConst.getIntValue());
      } else {
        assert type == NumericType.LONG;
        newConst = new ConstNumber(value, ~sourceConst.getLongValue());
      }
      return new ConstLatticeElement(newConst);
    }
    return Bottom.getInstance();
  }

  @Override
  public void buildDex(DexBuilder builder) {
    assert builder.getOptions().canUseNotInstruction();
    com.debughelper.tools.r8.code.Instruction instruction;
    int dest = builder.allocatedRegister(dest(), getNumber());
    int src = builder.allocatedRegister(source(), getNumber());
    switch (type) {
      case INT:
        instruction = new NotInt(dest, src);
        break;
      case LONG:
        instruction = new NotLong(dest, src);
        break;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
    builder.add(this, instruction);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    return other.isNot() && other.asNot().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.ordinal() - other.asNot().type.ordinal();
  }

  @Override
  public boolean isNot() {
    return true;
  }

  @Override
  public Not asNot() {
    return this;
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    helper.loadInValues(this, it);
    helper.storeOutValue(this, it);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(new CfConstNumber(-1, ValueType.fromNumericType(type)));
    builder.add(new CfLogicalBinop(CfLogicalBinop.Opcode.Xor, type));
  }
}
