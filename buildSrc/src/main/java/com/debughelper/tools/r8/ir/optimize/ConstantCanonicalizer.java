// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.ConstNumber;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.ir.code.Value;

import it.unimi.dsi.fastutil.Hash.Strategy;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenCustomHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectSortedMap.FastSortedEntrySet;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonicalize constants.
 */
public class ConstantCanonicalizer {

  private static final int MAX_CANONICALIZED_CONSTANT = 15;

  public static void canonicalize(com.debughelper.tools.r8.ir.code.IRCode code) {
    Object2ObjectLinkedOpenCustomHashMap<com.debughelper.tools.r8.ir.code.ConstNumber, List<com.debughelper.tools.r8.ir.code.Value>> valuesDefinedByConstant =
        new Object2ObjectLinkedOpenCustomHashMap<>(
            new Strategy<com.debughelper.tools.r8.ir.code.ConstNumber>() {
              @Override
              public int hashCode(com.debughelper.tools.r8.ir.code.ConstNumber constNumber) {
                return Long.hashCode(constNumber.getRawValue()) +
                    13 * constNumber.outType().hashCode();
              }

              @Override
              public boolean equals(com.debughelper.tools.r8.ir.code.ConstNumber a, com.debughelper.tools.r8.ir.code.ConstNumber b) {
                // Constants with local info must not be canonicalized and must be filtered.
                assert a == null || !a.outValue().hasLocalInfo();
                assert b == null || !b.outValue().hasLocalInfo();
                return a != null &&
                    b != null &&
                    a.identicalNonValueNonPositionParts(b);
              }
            });

    // Collect usages of constants that can be canonicalized. Constants that are used by invoke
    // range are not be canonicalized to be compliant with the optimization splitrangeInvokeConstant
    // that gives the register allocator more freedom in assigning register to ranged invokes
    // which can greatly reduce the number of register needed (and thereby code size as well). Thus
    // no need to do a transformation that should be removed later by another optimization.
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      com.debughelper.tools.r8.ir.code.InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction current = it.next();
        if (current.isConstNumber() &&
            !current.outValue().hasLocalInfo() &&
            !constantUsedByInvokeRange(current.asConstNumber())) {
          List<com.debughelper.tools.r8.ir.code.Value> oldValuesDefinedByConstant = valuesDefinedByConstant.get(current);
          if (oldValuesDefinedByConstant == null) {
            oldValuesDefinedByConstant = new ArrayList<>();
            valuesDefinedByConstant.put(current.asConstNumber(), oldValuesDefinedByConstant);
          }
          oldValuesDefinedByConstant.add(current.outValue());
        }
      }
    }

    if (!valuesDefinedByConstant.isEmpty()) {
      FastSortedEntrySet<com.debughelper.tools.r8.ir.code.ConstNumber, List<com.debughelper.tools.r8.ir.code.Value>> entries =
          valuesDefinedByConstant.object2ObjectEntrySet();
      // Sort the most frequently used constant first and exclude constant use only one time, such
      // as the {@code MAX_CANONICALIZED_CONSTANT} will canonicalized into the entry block.
      entries.stream()
          .filter(a -> a.getValue().size() > 1)
          .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
          .limit(MAX_CANONICALIZED_CONSTANT)
          .forEach((entry) -> {
            com.debughelper.tools.r8.ir.code.ConstNumber canonicalizedConstant = entry.getKey().asConstNumber();
            com.debughelper.tools.r8.ir.code.ConstNumber newConst = com.debughelper.tools.r8.ir.code.ConstNumber.copyOf(code, canonicalizedConstant);
            insertCanonicalizedConstant(code, newConst);
            newConst.setPosition(Position.none());
            for (Value outValue : entry.getValue()) {
              outValue.replaceUsers(newConst.outValue());
            }
          });
    }

    code.removeAllTrivialPhis();
    assert code.isConsistentSSA();
  }

  private static void insertCanonicalizedConstant (IRCode code, com.debughelper.tools.r8.ir.code.ConstNumber canonicalizedConstant) {
    BasicBlock entryBlock = code.blocks.get(0);
    // Insert the constant instruction at the start of the block right after the argument
    // instructions. It is important that the const instruction is put before any instruction
    // that can throw exceptions (since the value could be used on the exceptional edge).
    InstructionListIterator it = entryBlock.listIterator();
    while (it.hasNext()) {
      if (!it.next().isArgument()) {
        it.previous();
        break;
      }
    }
    it.add(canonicalizedConstant);
  }

  private static boolean constantUsedByInvokeRange(ConstNumber constant) {
    for (Instruction user : constant.outValue().uniqueUsers()) {
      if (user.isInvoke() && user.asInvoke().requiredArgumentRegisters() > 5) {
        return true;
      }
    }
    return false;
  }
}
