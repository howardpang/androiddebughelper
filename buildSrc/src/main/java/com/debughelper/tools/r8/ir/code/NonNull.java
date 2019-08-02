// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.code;

import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.conversion.CfBuilder;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import java.util.function.Function;

public class NonNull extends com.debughelper.tools.r8.ir.code.Instruction {
  private final static String ERROR_MESSAGE = "This fake IR should be removed after inlining.";

  final com.debughelper.tools.r8.ir.code.Instruction origin;

  public NonNull(Value dest, Value src, com.debughelper.tools.r8.ir.code.Instruction origin) {
    super(dest, src);
    assert !src.isNeverNull();
    dest.markNeverNull();
    this.origin = origin;
  }

  public Value dest() {
    return outValue;
  }

  public Value src() {
    return inValues.get(0);
  }

  public com.debughelper.tools.r8.ir.code.Instruction origin() {
    return origin;
  }

  @Override
  public boolean isNonNull() {
    return true;
  }

  @Override
  public NonNull asNonNull() {
    return this;
  }

  @Override
  public void buildDex(DexBuilder builder) {
    throw new Unreachable(ERROR_MESSAGE);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    throw new Unreachable(ERROR_MESSAGE);
  }

  @Override
  public int maxInValueRegister() {
    throw new Unreachable(ERROR_MESSAGE);
  }

  @Override
  public int maxOutValueRegister() {
    throw new Unreachable(ERROR_MESSAGE);
  }

  @Override
  public boolean isOutConstant() {
    return false;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(com.debughelper.tools.r8.ir.code.Instruction other) {
    return other.isNonNull();
  }

  @Override
  public int compareNonValueParts(Instruction other) {
    assert other instanceof NonNull;
    return 0;
  }

  @Override
  public Constraint inliningConstraint(AppInfoWithLiveness info, DexType invocationContext) {
    return Constraint.ALWAYS;
  }

  @Override
  public TypeLatticeElement evaluate(
      AppInfo appInfo, Function<Value, TypeLatticeElement> getLatticeElement) {
    TypeLatticeElement l = getLatticeElement.apply(src());
    if (l.isClassTypeLatticeElement() || l.isArrayTypeLatticeElement()) {
      return l.asNonNullable();
    }
    return l;
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    throw new Unreachable(ERROR_MESSAGE);
  }
}
