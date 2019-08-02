// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.analysis.type;

import com.debughelper.tools.r8.ir.analysis.type.Bottom;
import com.debughelper.tools.r8.ir.analysis.type.Top;
import com.debughelper.tools.r8.ir.analysis.type.TypeEnvironment;
import com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.debughelper.tools.r8.ir.code.Phi;
import com.debughelper.tools.r8.ir.code.Value;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public class TypeAnalysis implements com.debughelper.tools.r8.ir.analysis.type.TypeEnvironment {
  private final com.debughelper.tools.r8.graph.AppInfo appInfo;
  private final com.debughelper.tools.r8.graph.DexEncodedMethod encodedMethod;

  private final Deque<com.debughelper.tools.r8.ir.code.Value> worklist = new ArrayDeque<>();
  private final Map<com.debughelper.tools.r8.ir.code.Value, com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement> typeMap = Maps.newHashMap();

  public TypeAnalysis(AppInfo appInfo, DexEncodedMethod encodedMethod, IRCode code) {
    this.appInfo = appInfo;
    this.encodedMethod = encodedMethod;
    analyzeBlocks(code.topologicallySortedBlocks());
  }

  @Override
  public void analyze() {
    while (!worklist.isEmpty()) {
      analyzeValue(worklist.poll());
    }
  }

  @Override
  public void analyzeBlocks(List<com.debughelper.tools.r8.ir.code.BasicBlock> blocks) {
    assert worklist.isEmpty();
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : blocks) {
      processBasicBlock(block);
    }
    analyze();
  }

  @Override
  public void enqueue(com.debughelper.tools.r8.ir.code.Value v) {
    assert v != null;
    if (!worklist.contains(v)) {
      worklist.add(v);
    }
  }

  private void processBasicBlock(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    int argumentsSeen = encodedMethod.accessFlags.isStatic() ? 0 : -1;
    for (com.debughelper.tools.r8.ir.code.Instruction instruction : block.getInstructions()) {
      com.debughelper.tools.r8.ir.code.Value outValue = instruction.outValue();
      // Argument, a quasi instruction, needs to be handled specially:
      //   1) We can derive its type from the method signature; and
      //   2) It does not have an out value, so we can skip the env updating.
      if (instruction.isArgument()) {
        com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement derived;
        if (argumentsSeen < 0) {
          // Receiver
          derived = com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement.fromDexType(encodedMethod.method.holder, false);
        } else {
          com.debughelper.tools.r8.graph.DexType argType = encodedMethod.method.proto.parameters.values[argumentsSeen];
          derived = com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement.fromDexType(argType, true);
        }
        argumentsSeen++;
        assert outValue != null;
        updateTypeOfValue(outValue, derived);
      } else {
        if (outValue != null) {
          enqueue(outValue);
        }
      }
    }
    for (com.debughelper.tools.r8.ir.code.Phi phi : block.getPhis()) {
      enqueue(phi);
    }
  }

  private void analyzeValue(com.debughelper.tools.r8.ir.code.Value value) {
    com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement derived =
        value.isPhi()
            ? computePhiType(value.asPhi())
            : value.definition.evaluate(appInfo, this::getLatticeElement);
    updateTypeOfValue(value, derived);
  }

  private void updateTypeOfValue(com.debughelper.tools.r8.ir.code.Value value, com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement type) {
    com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement current = getLatticeElement(value);
    if (current.equals(type)) {
      return;
    }
    // TODO(b/72693244): attach type lattice directly to Value.
    setLatticeElement(value, type);
    // propagate the type change to (instruction) users if any.
    for (Instruction instruction : value.uniqueUsers()) {
      com.debughelper.tools.r8.ir.code.Value outValue = instruction.outValue();
      if (outValue != null) {
        enqueue(outValue);
      }
    }
    // Propagate the type change to phi users if any.
    for (com.debughelper.tools.r8.ir.code.Phi phi : value.uniquePhiUsers()) {
      enqueue(phi);
    }
  }

  private com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement computePhiType(Phi phi) {
    // Type of phi(v1, v2, ..., vn) is the least upper bound of all those n operands.
    return com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement.join(
        appInfo, phi.getOperands().stream().map(this::getLatticeElement));
  }

  private void setLatticeElement(com.debughelper.tools.r8.ir.code.Value value, com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement type) {
    typeMap.put(value, type);
  }

  @Override
  public com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement getLatticeElement(com.debughelper.tools.r8.ir.code.Value value) {
    return typeMap.getOrDefault(value, Bottom.getInstance());
  }

  @Override
  public com.debughelper.tools.r8.graph.DexType getRefinedReceiverType(com.debughelper.tools.r8.ir.code.InvokeMethodWithReceiver invoke) {
    com.debughelper.tools.r8.graph.DexType receiverType = invoke.getInvokedMethod().getHolder();
    com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement lattice = getLatticeElement(invoke.getReceiver());
    if (lattice.isClassTypeLatticeElement()) {
      com.debughelper.tools.r8.graph.DexType refinedType = lattice.asClassTypeLatticeElement().getClassType();
      if (refinedType.isSubtypeOf(receiverType, appInfo)) {
        return refinedType;
      }
    }
    return receiverType;
  }

  private static final com.debughelper.tools.r8.ir.analysis.type.TypeEnvironment DEFAULT_ENVIRONMENT = new com.debughelper.tools.r8.ir.analysis.type.TypeEnvironment() {
    @Override
    public void analyze() {
    }

    @Override
    public void analyzeBlocks(List<BasicBlock> blocks) {
    }

    @Override
    public void enqueue(com.debughelper.tools.r8.ir.code.Value value) {
    }

    @Override
    public com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement getLatticeElement(com.debughelper.tools.r8.ir.code.Value value) {
      return Top.getInstance();
    }

    @Override
    public DexType getRefinedReceiverType(InvokeMethodWithReceiver invoke) {
      return invoke.getInvokedMethod().holder;
    }
  };

  // TODO(b/72693244): By annotating type lattice to value, remove the default type env at all.
  public static TypeEnvironment getDefaultTypeEnvironment() {
    return DEFAULT_ENVIRONMENT;
  }

  @VisibleForTesting
  void forEach(BiConsumer<Value, TypeLatticeElement> consumer) {
    typeMap.forEach(consumer);
  }
}
