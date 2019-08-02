// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.cf.code.CfInstanceOf;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.analysis.type.PrimitiveTypeLatticeElement;
import com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import java.util.function.Function;

public class InstanceOf extends com.debughelper.tools.r8.ir.code.Instruction {

  private final DexType type;

  public InstanceOf(Value dest, Value value, DexType type) {
    super(dest, value);
    this.type = type;
  }

  public DexType type() {
    return type;
  }

  public Value dest() {
    return outValue;
  }

  public Value value() {
    return inValues.get(0);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    int dest = builder.allocatedRegister(dest(), getNumber());
    int value = builder.allocatedRegister(value(), getNumber());
    builder.add(this, new com.debughelper.tools.r8.code.InstanceOf(dest, value, type));
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U4BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    return Constants.U4BIT_MAX;
  }

  @Override
  public boolean instructionTypeCanThrow() {
    return true;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    return other.isInstanceOf() && other.asInstanceOf().type == type;
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    return type.slowCompareTo(other.asInstanceOf().type);
  }

  @Override
  public boolean isInstanceOf() {
    return true;
  }

  @Override
  public InstanceOf asInstanceOf() {
    return this;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithLiveness info, DexType invocationContext) {
    return Constraint.classIsVisible(invocationContext, type, info);
  }

  @Override
  public TypeLatticeElement evaluate(
      AppInfo appInfo, Function<Value, TypeLatticeElement> getLatticeElement) {
    return PrimitiveTypeLatticeElement.getInstance();
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    helper.loadInValues(this, it);
    helper.storeOutValue(this, it);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(new CfInstanceOf(type));
  }
}
