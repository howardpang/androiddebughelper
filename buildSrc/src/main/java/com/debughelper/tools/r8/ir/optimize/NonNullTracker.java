// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.DominatorTree;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.If;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionIterator;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.NonNull;
import com.debughelper.tools.r8.ir.code.Phi;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class NonNullTracker {

  public NonNullTracker() {
  }

  @VisibleForTesting
  static boolean throwsOnNullInput(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    return (instruction.isInvokeMethodWithReceiver() && !instruction.isInvokeDirect())
        || instruction.isInstanceGet()
        || instruction.isInstancePut()
        || instruction.isArrayGet()
        || instruction.isArrayPut()
        || instruction.isArrayLength()
        || instruction.isMonitor();
  }

  private com.debughelper.tools.r8.ir.code.Value getNonNullInput(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    if (instruction.isInvokeMethodWithReceiver()) {
      return instruction.asInvokeMethodWithReceiver().getReceiver();
    } else if (instruction.isInstanceGet()) {
      return instruction.asInstanceGet().object();
    } else if (instruction.isInstancePut()) {
      return instruction.asInstancePut().object();
    } else if (instruction.isArrayGet()) {
      return instruction.asArrayGet().array();
    } else if (instruction.isArrayPut()) {
      return instruction.asArrayPut().array();
    } else if (instruction.isArrayLength()) {
      return instruction.asArrayLength().array();
    } else if (instruction.isMonitor()) {
      return instruction.asMonitor().object();
    }
    throw new Unreachable("Should conform to throwsOnNullInput.");
  }

  public void addNonNull(com.debughelper.tools.r8.ir.code.IRCode code) {
    addNonNullInPart(code, code.blocks.listIterator(), b -> true);
  }

  public void addNonNullInPart(
          com.debughelper.tools.r8.ir.code.IRCode code, ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blockIterator, Predicate<com.debughelper.tools.r8.ir.code.BasicBlock> blockTester) {
    while (blockIterator.hasNext()) {
      com.debughelper.tools.r8.ir.code.BasicBlock block = blockIterator.next();
      if (!blockTester.test(block)) {
        continue;
      }
      // Add non-null after instructions that implicitly indicate receiver/array is not null.
      com.debughelper.tools.r8.ir.code.InstructionListIterator iterator = block.listIterator();
      while (iterator.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction current = iterator.next();
        if (!throwsOnNullInput(current)) {
          continue;
        }
        com.debughelper.tools.r8.ir.code.Value knownToBeNonNullValue = getNonNullInput(current);
        // Avoid adding redundant non-null instruction.
        if (knownToBeNonNullValue.isNeverNull()) {
          // Otherwise, we will have something like:
          // non_null_rcv <- non-null(rcv)
          // ...
          // another_rcv <- non-null(non_null_rcv)
          continue;
        }
        // First, if the current block has catch handler, split into two blocks, e.g.,
        //
        // ...x
        // invoke(rcv, ...)
        // ...y
        //
        //   ~>
        //
        // ...x
        // invoke(rcv, ...)
        // goto A
        //
        // A: ...y // blockWithNonNullInstruction
        //
        com.debughelper.tools.r8.ir.code.BasicBlock blockWithNonNullInstruction =
            block.hasCatchHandlers() ? iterator.split(code, blockIterator) : block;
        // Next, add non-null fake IR, e.g.,
        // ...x
        // invoke(rcv, ...)
        // goto A
        // ...
        // A: non_null_rcv <- non-null(rcv)
        // ...y
        com.debughelper.tools.r8.ir.code.Value nonNullValue =
            code.createValue(ValueType.OBJECT, knownToBeNonNullValue.getLocalInfo());
        com.debughelper.tools.r8.ir.code.NonNull nonNull = new com.debughelper.tools.r8.ir.code.NonNull(nonNullValue, knownToBeNonNullValue, current);
        nonNull.setPosition(current.getPosition());
        if (blockWithNonNullInstruction !=  block) {
          // If we split, add non-null IR on top of the new split block.
          blockWithNonNullInstruction.listIterator().add(nonNull);
        } else {
          // Otherwise, just add it to the current block at the position of the iterator.
          iterator.add(nonNull);
        }
        // Then, replace all users of the original value that are dominated by either the current
        // block or the new split-off block. Since NPE can be explicitly caught, nullness should be
        // propagated through dominance.
        Set<com.debughelper.tools.r8.ir.code.Instruction> users = knownToBeNonNullValue.uniqueUsers();
        Set<com.debughelper.tools.r8.ir.code.Instruction> dominatedUsers = Sets.newIdentityHashSet();
        Map<com.debughelper.tools.r8.ir.code.Phi, IntList> dominatedPhiUsersWithPositions = new IdentityHashMap<>();
        com.debughelper.tools.r8.ir.code.DominatorTree dominatorTree = new com.debughelper.tools.r8.ir.code.DominatorTree(code);
        Set<com.debughelper.tools.r8.ir.code.BasicBlock> dominatedBlocks = Sets.newIdentityHashSet();
        for (com.debughelper.tools.r8.ir.code.BasicBlock dominatee : dominatorTree.dominatedBlocks(blockWithNonNullInstruction)) {
          dominatedBlocks.add(dominatee);
          com.debughelper.tools.r8.ir.code.InstructionListIterator dominateeIterator = dominatee.listIterator();
          if (dominatee == blockWithNonNullInstruction) {
            // In the block with the inserted non null instruction, skip instructions up to and
            // including the newly inserted instruction.
            dominateeIterator.nextUntil(instruction -> instruction == nonNull);
          }
          while (dominateeIterator.hasNext()) {
            com.debughelper.tools.r8.ir.code.Instruction potentialUser = dominateeIterator.next();
            assert potentialUser != nonNull;
            if (users.contains(potentialUser)) {
              dominatedUsers.add(potentialUser);
            }
          }
        }
        for (com.debughelper.tools.r8.ir.code.Phi user : knownToBeNonNullValue.uniquePhiUsers()) {
          IntList dominatedPredecessorIndexes =
              findDominatedPredecessorIndexesInPhi(user, knownToBeNonNullValue, dominatedBlocks);
          if (!dominatedPredecessorIndexes.isEmpty()) {
            dominatedPhiUsersWithPositions.put(user, dominatedPredecessorIndexes);
          }
        }
        knownToBeNonNullValue.replaceSelectiveUsers(
            nonNullValue, dominatedUsers, dominatedPhiUsersWithPositions);
      }

      // Add non-null on top of the successor block if the current block ends with a null check.
      if (block.exit().isIf() && block.exit().asIf().isZeroTest()) {
        // if v EQ blockX
        // ... (fallthrough)
        // blockX: ...
        //
        //   ~>
        //
        // if v EQ blockX
        // non_null_value <- non-null(v)
        // ...
        // blockX: ...
        //
        // or
        //
        // if v NE blockY
        // ...
        // blockY: ...
        //
        //   ~>
        //
        // blockY: non_null_value <- non-null(v)
        // ...
        If theIf = block.exit().asIf();
        com.debughelper.tools.r8.ir.code.Value knownToBeNonNullValue = theIf.inValues().get(0);
        // Avoid adding redundant non-null instruction (or non-null of non-object types).
        if (knownToBeNonNullValue.outType().isObject() && !knownToBeNonNullValue.isNeverNull()) {
          com.debughelper.tools.r8.ir.code.BasicBlock target = theIf.targetFromNonNullObject();
          // Ignore uncommon empty blocks.
          if (!target.isEmpty()) {
            com.debughelper.tools.r8.ir.code.DominatorTree dominatorTree = new DominatorTree(code);
            // Make sure there are no paths to the target block without passing the current block.
            if (dominatorTree.dominatedBy(target, block)) {
              // Collect users of the original value that are dominated by the target block.
              Set<com.debughelper.tools.r8.ir.code.Instruction> dominatedUsers = Sets.newIdentityHashSet();
              Map<com.debughelper.tools.r8.ir.code.Phi, IntList> dominatedPhiUsersWithPositions = new IdentityHashMap<>();
              Set<com.debughelper.tools.r8.ir.code.BasicBlock> dominatedBlocks =
                  Sets.newHashSet(dominatorTree.dominatedBlocks(target));
              for (com.debughelper.tools.r8.ir.code.Instruction user : knownToBeNonNullValue.uniqueUsers()) {
                if (dominatedBlocks.contains(user.getBlock())) {
                  dominatedUsers.add(user);
                }
              }
              for (com.debughelper.tools.r8.ir.code.Phi user : knownToBeNonNullValue.uniquePhiUsers()) {
                IntList dominatedPredecessorIndexes = findDominatedPredecessorIndexesInPhi(
                    user, knownToBeNonNullValue, dominatedBlocks);
                if (!dominatedPredecessorIndexes.isEmpty()) {
                  dominatedPhiUsersWithPositions.put(user, dominatedPredecessorIndexes);
                }
              }
              // Avoid adding a non-null for the value without meaningful users.
              if (!dominatedUsers.isEmpty() || !dominatedPhiUsersWithPositions.isEmpty()) {
                com.debughelper.tools.r8.ir.code.Value nonNullValue = code.createValue(
                    knownToBeNonNullValue.outType(), knownToBeNonNullValue.getLocalInfo());
                com.debughelper.tools.r8.ir.code.NonNull nonNull = new com.debughelper.tools.r8.ir.code.NonNull(nonNullValue, knownToBeNonNullValue, theIf);
                InstructionListIterator targetIterator = target.listIterator();
                nonNull.setPosition(targetIterator.next().getPosition());
                targetIterator.previous();
                targetIterator.add(nonNull);
                knownToBeNonNullValue.replaceSelectiveUsers(
                    nonNullValue, dominatedUsers, dominatedPhiUsersWithPositions);
              }
            }
          }
        }
      }
    }
  }

  private IntList findDominatedPredecessorIndexesInPhi(
          Phi user, com.debughelper.tools.r8.ir.code.Value knownToBeNonNullValue, Set<com.debughelper.tools.r8.ir.code.BasicBlock> dominatedBlocks) {
    assert user.getOperands().contains(knownToBeNonNullValue);
    List<com.debughelper.tools.r8.ir.code.Value> operands = user.getOperands();
    List<com.debughelper.tools.r8.ir.code.BasicBlock> predecessors = user.getBlock().getPredecessors();
    assert operands.size() == predecessors.size();

    IntList predecessorIndexes = new IntArrayList();
    int index = 0;
    Iterator<com.debughelper.tools.r8.ir.code.Value> operandIterator = operands.iterator();
    Iterator<com.debughelper.tools.r8.ir.code.BasicBlock> predecessorIterator = predecessors.iterator();
    while (operandIterator.hasNext() && predecessorIterator.hasNext()) {
      com.debughelper.tools.r8.ir.code.Value operand = operandIterator.next();
      BasicBlock predecessor = predecessorIterator.next();
      // When this phi is chosen to be known-to-be-non-null value,
      // check if the corresponding predecessor is dominated by the block where non-null is added.
      if (operand == knownToBeNonNullValue && dominatedBlocks.contains(predecessor)) {
        predecessorIndexes.add(index);
      }

      index++;
    }
    return predecessorIndexes;
  }

  public void cleanupNonNull(IRCode code) {
    InstructionIterator it = code.instructionIterator();
    boolean needToCheckTrivialPhis = false;
    while (it.hasNext()) {
      Instruction instruction = it.next();
      // non_null_rcv <- non-null(rcv)  // deleted
      // ...
      // non_null_rcv#foo
      //
      //  ~>
      //
      // rcv#foo
      if (instruction.isNonNull()) {
        NonNull nonNull = instruction.asNonNull();
        com.debughelper.tools.r8.ir.code.Value src = nonNull.src();
        Value dest = nonNull.dest();
        needToCheckTrivialPhis = needToCheckTrivialPhis || dest.uniquePhiUsers().size() != 0;
        dest.replaceUsers(src);
        it.remove();
      }
    }
    // non-null might introduce a phi, e.g.,
    // non_null_rcv <- non-null(rcv)
    // ...
    // v <- phi(rcv, non_null_rcv)
    //
    // Cleaning up that non-null may result in a trivial phi:
    // v <- phi(rcv, rcv)
    if (needToCheckTrivialPhis) {
      code.removeAllTrivialPhis();
    }
  }

}
