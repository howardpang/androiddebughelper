// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.analysis.type.TypeEnvironment;
import com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.CheckCast;
import com.debughelper.tools.r8.ir.code.DominatorTree;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.InvokeInterface;
import com.debughelper.tools.r8.ir.code.InvokeVirtual;
import com.debughelper.tools.r8.ir.code.NonNull;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.shaking.Enqueuer;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.IdentityHashMap;
import java.util.ListIterator;
import java.util.Map;

public class Devirtualizer {

  private final Enqueuer.AppInfoWithLiveness appInfo;

  public Devirtualizer(Enqueuer.AppInfoWithLiveness appInfo) {
    this.appInfo = appInfo;
  }

  public void devirtualizeInvokeInterface(
          IRCode code, TypeEnvironment typeEnvironment, com.debughelper.tools.r8.graph.DexType invocationContext) {
    Map<com.debughelper.tools.r8.ir.code.InvokeInterface, com.debughelper.tools.r8.ir.code.InvokeVirtual> devirtualizedCall = new IdentityHashMap<>();
    com.debughelper.tools.r8.ir.code.DominatorTree dominatorTree = new com.debughelper.tools.r8.ir.code.DominatorTree(code);
    Map<com.debughelper.tools.r8.ir.code.Value, Map<com.debughelper.tools.r8.graph.DexType, com.debughelper.tools.r8.ir.code.Value>> castedReceiverCache = new IdentityHashMap<>();

    ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blocks = code.listIterator();
    while (blocks.hasNext()) {
      com.debughelper.tools.r8.ir.code.BasicBlock block = blocks.next();
      InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction current = it.next();

        // (out <-) invoke-interface rcv_i, ... I#foo
        // ...  // could be split due to catch handlers
        // non_null_rcv <- non-null rcv_i
        //
        //   ~>
        //
        // rcv_c <- check-cast C rcv_i
        // (out <-) invoke-virtual rcv_c, ... C#foo
        // ...
        // non_null_rcv <- non-null rcv_c  // <- Update the input rcv to the non-null, too.
        if (current.isNonNull()) {
          NonNull nonNull = current.asNonNull();
          Instruction origin = nonNull.origin();
          if (origin.isInvokeInterface()
              && devirtualizedCall.containsKey(origin.asInvokeInterface())) {
            com.debughelper.tools.r8.ir.code.InvokeVirtual devirtualizedInvoke = devirtualizedCall.get(origin.asInvokeInterface());
            if (dominatorTree.dominatedBy(block, devirtualizedInvoke.getBlock())) {
              nonNull.src().replaceSelectiveUsers(
                  devirtualizedInvoke.getReceiver(), ImmutableSet.of(nonNull), ImmutableMap.of());
            }
          }
        }

        if (!current.isInvokeInterface()) {
          continue;
        }
        InvokeInterface invoke = current.asInvokeInterface();
        DexEncodedMethod target =
            invoke.computeSingleTarget(appInfo, typeEnvironment, invocationContext);
        if (target == null) {
          continue;
        }
        DexType holderType = target.method.getHolder();
        DexClass holderClass = appInfo.definitionFor(holderType);
        // Make sure we are not landing on another interface, e.g., interface's default method.
        if (holderClass == null || holderClass.isInterface()) {
          continue;
        }
        // Due to the potential downcast below, make sure the new target holder is visible.
        Inliner.Constraint visibility = Inliner.Constraint.classIsVisible(invocationContext, holderType, appInfo);
        if (visibility == Inliner.Constraint.NEVER) {
          continue;
        }

        com.debughelper.tools.r8.ir.code.InvokeVirtual devirtualizedInvoke =
            new InvokeVirtual(target.method, invoke.outValue(), invoke.inValues());
        it.replaceCurrentInstruction(devirtualizedInvoke);
        devirtualizedCall.put(invoke, devirtualizedInvoke);

        // We may need to add downcast together. E.g.,
        // i <- check-cast I o  // suppose it is known to be of type class A, not interface I
        // (out <-) invoke-interface i, ... I#foo
        //
        //  ~>
        //
        // i <- check-cast I o  // could be removed by {@link CodeRewriter#removeCasts}.
        // a <- check-cast A i  // Otherwise ART verification error.
        // (out <-) invoke-virtual a, ... A#foo
        if (holderType != invoke.getInvokedMethod().getHolder()) {
          com.debughelper.tools.r8.ir.code.Value receiver = invoke.getReceiver();
          com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement receiverTypeLattice = typeEnvironment.getLatticeElement(receiver);
          com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement castTypeLattice =
              com.debughelper.tools.r8.ir.analysis.type.TypeLatticeElement.fromDexType(holderType, receiverTypeLattice.isNullable());
          // Avoid adding trivial cast and up-cast.
          // We should not use strictlyLessThan(castType, receiverType), which detects downcast,
          // due to side-casts, e.g., A (unused) < I, B < I, and cast from A to B.
          if (!TypeLatticeElement.lessThanOrEqual(appInfo, receiverTypeLattice, castTypeLattice)) {
            com.debughelper.tools.r8.ir.code.Value newReceiver = null;
            // If this value is ever downcast'ed to the same holder type before, and that casted
            // value is safely accessible, i.e., the current line is dominated by that cast, use it.
            // Otherwise, we will see something like:
            // ...
            // a1 <- check-cast A i
            // invoke-virtual a1, ... A#m1 (from I#m1)
            // ...
            // a2 <- check-cast A i  // We should be able to reuse a1 here!
            // invoke-virtual a2, ... A#m2 (from I#m2)
            if (castedReceiverCache.containsKey(receiver)
                && castedReceiverCache.get(receiver).containsKey(holderType)) {
              Value cachedReceiver = castedReceiverCache.get(receiver).get(holderType);
              if (dominatorTree.dominatedBy(block, cachedReceiver.definition.getBlock())) {
                newReceiver = cachedReceiver;
              }
            }

            // No cached, we need a new downcast'ed receiver.
            if (newReceiver == null) {
              newReceiver =
                  receiver.definition != null
                      ? code.createValue(receiver.outType(), receiver.definition.getLocalInfo())
                      : code.createValue(receiver.outType());
              // Cache the new receiver with a narrower type to avoid redundant checkcast.
              castedReceiverCache.putIfAbsent(receiver, new IdentityHashMap<>());
              castedReceiverCache.get(receiver).put(holderType, newReceiver);
              com.debughelper.tools.r8.ir.code.CheckCast checkCast = new CheckCast(newReceiver, receiver, holderType);
              checkCast.setPosition(invoke.getPosition());

              // We need to add this checkcast *before* the devirtualized invoke-virtual.
              assert it.peekPrevious() == devirtualizedInvoke;
              it.previous();
              // If the current block has catch handlers, split the new checkcast on its own block.
              // Because checkcast is also a throwing instr, we should split before adding it.
              // Otherwise, catch handlers are bound to a block with checkcast, not invoke IR.
              BasicBlock blockWithDevirtualizedInvoke =
                  block.hasCatchHandlers() ? it.split(code, blocks) : block;
              if (blockWithDevirtualizedInvoke != block) {
                // If we split, add the new checkcast at the end of the currently visiting block.
                it = block.listIterator(block.getInstructions().size());
                it.previous();
                it.add(checkCast);
                // Update the dominator tree after the split.
                dominatorTree = new DominatorTree(code);
                // Restore the cursor.
                it = blockWithDevirtualizedInvoke.listIterator();
                assert it.peekNext() == devirtualizedInvoke;
                it.next();
              } else {
                // Otherwise, just add it to the current block at the position of the iterator.
                it.add(checkCast);
                // Restore the cursor.
                assert it.peekNext() == devirtualizedInvoke;
                it.next();
              }
            }

            receiver.replaceSelectiveUsers(
                newReceiver, ImmutableSet.of(devirtualizedInvoke), ImmutableMap.of());
            // TODO(b/72693244): Analyze it when creating a new Value or after replace*Users
            typeEnvironment.enqueue(newReceiver);
            typeEnvironment.analyze();
          }
        }
      }
    }
    assert code.isConsistentSSA();
  }

}
