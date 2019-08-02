// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.conversion;

import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.If;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.Phi;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.code.ValueType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Type constraint resolver that ensures that all SSA values have a "precise" type, ie, every value
 * must be an element of exactly one of: object, int, float, long or double.
 *
 * <p>The resolution is a union-find over the SSA values, linking any type with an imprecise type to
 * a parent value that has either the same imprecise type or a precise one. SSA values are linked if
 * there type is constrained to be the same. This happens in two places:
 *
 * <ul>
 *   <li>For phis, the out value and all operand values must have the same type
 *   <li>For if-{eq,ne} instructions, the input values must have the same type.
 * </ul>
 *
 * <p>All other constraints on types have been computed duing IR construction where every call to
 * readRegister(ValueType) will constrain the type of the SSA value that the read resolves to.
 */
public class TypeConstraintResolver {

  private final Map<com.debughelper.tools.r8.ir.code.Value, com.debughelper.tools.r8.ir.code.Value> unificationParents = new HashMap<>();

  public void resolve(IRCode code) {
    List<com.debughelper.tools.r8.ir.code.Value> impreciseValues = new ArrayList<>();
    for (BasicBlock block : code.blocks) {
      for (Phi phi : block.getPhis()) {
        if (!phi.outType().isPreciseType()) {
          impreciseValues.add(phi);
        }
        for (com.debughelper.tools.r8.ir.code.Value value : phi.getOperands()) {
          merge(phi, value);
        }
      }
      for (Instruction instruction : block.getInstructions()) {
        if (instruction.outValue() != null && !instruction.outType().isPreciseType()) {
          impreciseValues.add(instruction.outValue());
        }

        if (instruction.isIf() && instruction.inValues().size() == 2) {
          com.debughelper.tools.r8.ir.code.If ifInstruction = instruction.asIf();
          assert !ifInstruction.isZeroTest();
          com.debughelper.tools.r8.ir.code.If.Type type = ifInstruction.getType();
          if (type == com.debughelper.tools.r8.ir.code.If.Type.EQ || type == If.Type.NE) {
            merge(ifInstruction.inValues().get(0), ifInstruction.inValues().get(1));
          }
        }

        // TODO(zerny): Once we have detailed value types we must join the array element-type with
        // the value/dest for array-put/get instructions.
      }
    }
    for (com.debughelper.tools.r8.ir.code.Value value : impreciseValues) {
      value.constrainType(getPreciseType(value));
    }
  }

  private void merge(com.debughelper.tools.r8.ir.code.Value value1, com.debughelper.tools.r8.ir.code.Value value2) {
    link(canonical(value1), canonical(value2));
  }

  private com.debughelper.tools.r8.ir.code.ValueType getPreciseType(com.debughelper.tools.r8.ir.code.Value value) {
    com.debughelper.tools.r8.ir.code.ValueType type = canonical(value).outType();
    return type != com.debughelper.tools.r8.ir.code.ValueType.INT_OR_FLOAT_OR_NULL ? type : com.debughelper.tools.r8.ir.code.ValueType.INT_OR_FLOAT;
  }

  private void link(com.debughelper.tools.r8.ir.code.Value canonical1, com.debughelper.tools.r8.ir.code.Value canonical2) {
    if (canonical1 == canonical2) {
      return;
    }
    com.debughelper.tools.r8.ir.code.ValueType type1 = canonical1.outType();
    ValueType type2 = canonical2.outType();
    if (type1.isPreciseType() && type2.isPreciseType()) {
      if (type1 != type2) {
        throw new CompilationError(
            "Cannot unify types for values "
                + canonical1
                + ":"
                + type1
                + " and "
                + canonical2
                + ":"
                + type2);
      }
      return;
    }
    if (type1.isPreciseType()) {
      unificationParents.put(canonical2, canonical1);
    } else {
      unificationParents.put(canonical1, canonical2);
    }
  }

  // Find root with path-compression.
  private com.debughelper.tools.r8.ir.code.Value canonical(com.debughelper.tools.r8.ir.code.Value value) {
    com.debughelper.tools.r8.ir.code.Value parent = value;
    while (parent != null) {
      Value grandparent = unificationParents.get(parent);
      if (grandparent != null) {
        unificationParents.put(value, grandparent);
      }
      value = parent;
      parent = grandparent;
    }
    return value;
  }
}
