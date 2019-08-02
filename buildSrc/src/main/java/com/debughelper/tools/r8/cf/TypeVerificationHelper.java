// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf;

import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.graph.AppInfoWithSubtyping;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionIterator;
import com.debughelper.tools.r8.ir.code.Value;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// Compute the types of all reference values.
// The actual types are needed to emit stack-map frames for Java 1.6 and above.
public class TypeVerificationHelper {

  private final com.debughelper.tools.r8.ir.code.IRCode code;
  private final DexItemFactory factory;
  private final AppInfoWithSubtyping appInfo;

  private Map<com.debughelper.tools.r8.ir.code.Value, DexType> types;

  public TypeVerificationHelper(IRCode code, DexItemFactory factory, AppInfoWithSubtyping appInfo) {
    this.code = code;
    this.factory = factory;
    this.appInfo = appInfo;
  }

  public DexItemFactory getFactory() {
    return factory;
  }

  public DexType getType(com.debughelper.tools.r8.ir.code.Value value) {
    assert value.outType().isObject();
    return types.get(value);
  }

  // Helper to compute the join of a set of reference types.
  public DexType join(Set<DexType> types) {
    // We should not be joining empty sets of types.
    assert !types.isEmpty();
    if (types.size() == 1) {
      return types.iterator().next();
    }
    Iterator<DexType> iterator = types.iterator();
    TypeLatticeElement join = getLatticeElement(iterator.next());
    while (iterator.hasNext()) {
      join = TypeLatticeElement.join(appInfo, join, getLatticeElement(iterator.next()));
    }
    // All types are reference types so the join is either a class or an array.
    if (join.isClassTypeLatticeElement()) {
      return join.asClassTypeLatticeElement().getClassType();
    } else if (join.isArrayTypeLatticeElement()) {
      return join.asArrayTypeLatticeElement().getArrayType();
    }
    throw new CompilationError("Unexpected join " + join + " of types: " +
        String.join(", ",
            types.stream().map(DexType::toSourceString).collect(Collectors.toList())));
  }

  private TypeLatticeElement getLatticeElement(DexType type) {
    return TypeLatticeElement.fromDexType(type, true);
  }

  public Map<com.debughelper.tools.r8.ir.code.Value, DexType> computeVerificationTypes() {
    types = new HashMap<>();
    Set<com.debughelper.tools.r8.ir.code.Value> worklist = new HashSet<>();
    {
      InstructionIterator it = code.instructionIterator();
      com.debughelper.tools.r8.ir.code.Instruction instruction = null;
      // Set the out-value types of each argument based on the method signature.
      int argumentIndex = code.method.accessFlags.isStatic() ? 0 : -1;
      while (it.hasNext()) {
        instruction = it.next();
        if (!instruction.isArgument()) {
          break;
        }
        DexType argumentType =
            (argumentIndex < 0)
                ? code.method.method.getHolder()
                : code.method.method.proto.parameters.values[argumentIndex];
        com.debughelper.tools.r8.ir.code.Value outValue = instruction.outValue();
        if (outValue.outType().isObject()) {
          types.put(outValue, argumentType);
          addUsers(outValue, worklist);
        }
        ++argumentIndex;
      }
      // Compute the out-value type of each normal instruction with an invariant out-value type.
      while (instruction != null) {
        assert !instruction.isArgument();
        if (instruction.outValue() != null && instruction.outType().isObject()) {
          com.debughelper.tools.r8.ir.code.Value outValue = instruction.outValue();
          if (instruction.hasInvariantVerificationType()) {
            DexType type = instruction.computeVerificationType(this);
            assert type != null;
            types.put(outValue, type);
            addUsers(outValue, worklist);
          }
        }
        instruction = it.hasNext() ? it.next() : null;
      }
    }
    // Compute the fixed-point of all the out-value types.
    while (!worklist.isEmpty()) {
      com.debughelper.tools.r8.ir.code.Value item = worklist.iterator().next();
      worklist.remove(item);
      assert item.outType().isObject();
      DexType previousType = types.get(item);
      DexType refinedType = computeVerificationType(item);
      if (previousType != refinedType) {
        types.put(item, refinedType);
        addUsers(item, worklist);
      }
    }
    return types;
  }

  private DexType computeVerificationType(com.debughelper.tools.r8.ir.code.Value value) {
    return value.isPhi()
        ? value.asPhi().computeVerificationType(this)
        : value.definition.computeVerificationType(this);
  }

  private static void addUsers(com.debughelper.tools.r8.ir.code.Value value, Set<Value> worklist) {
    worklist.addAll(value.uniquePhiUsers());
    for (Instruction instruction : value.uniqueUsers()) {
      if (instruction.outValue() != null
          && instruction.outType().isObject()
          && !instruction.hasInvariantVerificationType()) {
        worklist.add(instruction.outValue());
      }
    }
  }
}
