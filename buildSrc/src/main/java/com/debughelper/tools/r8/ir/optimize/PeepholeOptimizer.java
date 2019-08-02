// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.graph.DebugLocalInfo;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.ConstNumber;
import com.debughelper.tools.r8.ir.code.DebugLocalsChange;
import com.debughelper.tools.r8.ir.code.Goto;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.regalloc.LinearScanRegisterAllocator;
import com.debughelper.tools.r8.ir.regalloc.LiveIntervals;
import com.debughelper.tools.r8.ir.regalloc.RegisterAllocator;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.collect.ImmutableList;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap.Entry;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

public class PeepholeOptimizer {

  /**
   * Perform optimizations of the code with register assignments provided by the register allocator.
   */
  public static void optimize(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.regalloc.LinearScanRegisterAllocator allocator) {
    removeIdenticalPredecessorBlocks(code, allocator);
    removeRedundantInstructions(code, allocator);
    shareIdenticalBlockSuffix(code, allocator);
    assert code.isConsistentGraph();
  }

  /**
   * Identify common suffixes in predecessor blocks and share them.
   */
  private static void shareIdenticalBlockSuffix(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.ir.regalloc.RegisterAllocator allocator) {
    Collection<com.debughelper.tools.r8.ir.code.BasicBlock> blocks = code.blocks;
    com.debughelper.tools.r8.ir.code.BasicBlock normalExit = null;
    ImmutableList<com.debughelper.tools.r8.ir.code.BasicBlock> normalExits = code.computeNormalExitBlocks();
    if (normalExits.size() > 1) {
      normalExit = new com.debughelper.tools.r8.ir.code.BasicBlock();
      normalExit.getPredecessors().addAll(normalExits);
      blocks = new ArrayList<>(code.blocks);
      blocks.add(normalExit);
    }
    do {
      int startNumberOfNewBlock = code.getHighestBlockNumber() + 1;
      Map<com.debughelper.tools.r8.ir.code.BasicBlock, com.debughelper.tools.r8.ir.code.BasicBlock> newBlocks = new IdentityHashMap<>();
      for (com.debughelper.tools.r8.ir.code.BasicBlock block : blocks) {
        InstructionEquivalence equivalence = new InstructionEquivalence(allocator);
        // Group interesting predecessor blocks by their last instruction.
        Map<Wrapper<com.debughelper.tools.r8.ir.code.Instruction>, List<com.debughelper.tools.r8.ir.code.BasicBlock>> lastInstructionToBlocks = new HashMap<>();
        for (com.debughelper.tools.r8.ir.code.BasicBlock pred : block.getPredecessors()) {
          // Only deal with predecessors with one successor. This way we can move throwing
          // instructions as well since there are no handlers (or the handler is the same as the
          // normal control-flow block). Alternatively, we could move only non-throwing instructions
          // and allow both a goto edge and exception edges when the target does not start with a
          // MoveException instruction. However, that would require us to require rewriting of
          // catch handlers as well.
          if (pred.exit().isGoto()
              && pred.getSuccessors().size() == 1
              && pred.getInstructions().size() > 1) {
            List<com.debughelper.tools.r8.ir.code.Instruction> instructions = pred.getInstructions();
            com.debughelper.tools.r8.ir.code.Instruction lastInstruction = instructions.get(instructions.size() - 2);
            List<com.debughelper.tools.r8.ir.code.BasicBlock> value = lastInstructionToBlocks.computeIfAbsent(
                equivalence.wrap(lastInstruction), (k) -> new ArrayList<>());
            value.add(pred);
          } else if (pred.exit().isReturn()
              && pred.getSuccessors().isEmpty()
              && pred.getInstructions().size() > 2) {
            com.debughelper.tools.r8.ir.code.Instruction lastInstruction = pred.exit();
            List<com.debughelper.tools.r8.ir.code.BasicBlock> value =
                lastInstructionToBlocks.computeIfAbsent(
                    equivalence.wrap(lastInstruction), (k) -> new ArrayList<>());
            value.add(pred);
          }
        }
        // For each group of predecessors of size 2 or more, find the largest common suffix and
        // move that to a separate block.
        for (List<com.debughelper.tools.r8.ir.code.BasicBlock> predsWithSameLastInstruction : lastInstructionToBlocks.values()) {
          if (predsWithSameLastInstruction.size() < 2) {
            continue;
          }
          com.debughelper.tools.r8.ir.code.BasicBlock firstPred = predsWithSameLastInstruction.get(0);
          int commonSuffixSize = firstPred.getInstructions().size();
          for (int i = 1; i < predsWithSameLastInstruction.size(); i++) {
            com.debughelper.tools.r8.ir.code.BasicBlock pred = predsWithSameLastInstruction.get(i);
            assert pred.exit().isGoto() || pred.exit().isReturn();
            commonSuffixSize =
                Math.min(commonSuffixSize, sharedSuffixSize(firstPred, pred, allocator));
          }
          // Don't share a suffix that is just a single goto or return instruction.
          if (commonSuffixSize <= 1) {
            continue;
          }
          int blockNumber = startNumberOfNewBlock + newBlocks.size();
          com.debughelper.tools.r8.ir.code.BasicBlock newBlock =
              createAndInsertBlockForSuffix(
                  blockNumber,
                  commonSuffixSize,
                  predsWithSameLastInstruction,
                  block == normalExit ? null : block);
          newBlocks.put(predsWithSameLastInstruction.get(0), newBlock);
        }
      }
      ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blockIterator = code.listIterator();
      while (blockIterator.hasNext()) {
        com.debughelper.tools.r8.ir.code.BasicBlock block = blockIterator.next();
        if (newBlocks.containsKey(block)) {
          blockIterator.add(newBlocks.get(block));
        }
      }
      // Go through all the newly introduced blocks to find more common suffixes to share.
      blocks = newBlocks.values();
    } while (!blocks.isEmpty());
  }

  private static com.debughelper.tools.r8.ir.code.BasicBlock createAndInsertBlockForSuffix(
          int blockNumber, int suffixSize, List<com.debughelper.tools.r8.ir.code.BasicBlock> preds, com.debughelper.tools.r8.ir.code.BasicBlock successorBlock) {
    com.debughelper.tools.r8.ir.code.BasicBlock newBlock = com.debughelper.tools.r8.ir.code.BasicBlock.createGotoBlock(blockNumber);
    com.debughelper.tools.r8.ir.code.BasicBlock first = preds.get(0);
    assert (successorBlock != null && first.exit().isGoto())
        || (successorBlock == null && first.exit().isReturn());
    int offsetFromEnd = successorBlock == null ? 0 : 1;
    if (successorBlock == null) {
      newBlock.getInstructions().removeLast();
    }
    com.debughelper.tools.r8.ir.code.InstructionListIterator from =
        first.listIterator(first.getInstructions().size() - offsetFromEnd);
    Int2ReferenceMap<com.debughelper.tools.r8.graph.DebugLocalInfo> newBlockEntryLocals =
        (successorBlock == null || successorBlock.getLocalsAtEntry() == null)
            ? new Int2ReferenceOpenHashMap<>()
            : new Int2ReferenceOpenHashMap<>(successorBlock.getLocalsAtEntry());
    boolean movedThrowingInstruction = false;
    for (int i = offsetFromEnd; i < suffixSize; i++) {
      com.debughelper.tools.r8.ir.code.Instruction instruction = from.previous();
      movedThrowingInstruction = movedThrowingInstruction || instruction.instructionTypeCanThrow();
      newBlock.getInstructions().addFirst(instruction);
      instruction.setBlock(newBlock);
      if (instruction.isDebugLocalsChange()) {
        // Replay the debug local changes backwards to compute the entry state.
        DebugLocalsChange change = instruction.asDebugLocalsChange();
        for (int starting : change.getStarting().keySet()) {
          newBlockEntryLocals.remove(starting);
        }
        for (Entry<DebugLocalInfo> ending : change.getEnding().int2ReferenceEntrySet()) {
          newBlockEntryLocals.put(ending.getIntKey(), ending.getValue());
        }
      }
    }
    if (movedThrowingInstruction && first.hasCatchHandlers()) {
      newBlock.transferCatchHandlers(first);
    }
    for (com.debughelper.tools.r8.ir.code.BasicBlock pred : preds) {
      LinkedList<com.debughelper.tools.r8.ir.code.Instruction> instructions = pred.getInstructions();
      for (int i = 0; i < suffixSize; i++) {
        instructions.removeLast();
      }
      com.debughelper.tools.r8.ir.code.Goto jump = new com.debughelper.tools.r8.ir.code.Goto();
      jump.setBlock(pred);
      instructions.add(jump);
      newBlock.getPredecessors().add(pred);
      if (successorBlock != null) {
        pred.replaceSuccessor(successorBlock, newBlock);
        successorBlock.getPredecessors().remove(pred);
      } else {
        pred.getSuccessors().add(newBlock);
      }
      if (movedThrowingInstruction) {
        pred.clearCatchHandlers();
      }
    }
    newBlock.setLocalsAtEntry(newBlockEntryLocals);
    if (successorBlock != null) {
      newBlock.link(successorBlock);
    }
    return newBlock;
  }

  private static int sharedSuffixSize(
          com.debughelper.tools.r8.ir.code.BasicBlock block0, com.debughelper.tools.r8.ir.code.BasicBlock block1, com.debughelper.tools.r8.ir.regalloc.RegisterAllocator allocator) {
    assert block0.exit().isGoto() || block0.exit().isReturn();
    com.debughelper.tools.r8.ir.code.InstructionListIterator it0 = block0.listIterator(block0.getInstructions().size());
    InstructionListIterator it1 = block1.listIterator(block1.getInstructions().size());
    int suffixSize = 0;
    while (it0.hasPrevious() && it1.hasPrevious()) {
      com.debughelper.tools.r8.ir.code.Instruction i0 = it0.previous();
      com.debughelper.tools.r8.ir.code.Instruction i1 = it1.previous();
      if (!i0.identicalAfterRegisterAllocation(i1, allocator)) {
        return suffixSize;
      }
      suffixSize++;
    }
    return suffixSize;
  }

  /**
   * If two predecessors have the same code and successors. Replace one of them with an
   * empty block with a goto to the other.
   */
  private static void removeIdenticalPredecessorBlocks(com.debughelper.tools.r8.ir.code.IRCode code, RegisterAllocator allocator) {
    BasicBlockInstructionsEquivalence equivalence =
        new BasicBlockInstructionsEquivalence(code, allocator);
    // Locate one block at a time that has identical predecessors. Rewrite those predecessors and
    // then start over. Restarting when one blocks predecessors have been rewritten simplifies
    // the rewriting and reduces the size of the data structures.
    boolean changed;
    do {
      changed = false;
      for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
        Map<Wrapper<com.debughelper.tools.r8.ir.code.BasicBlock>, Integer> blockToIndex = new HashMap<>();
        for (int predIndex = 0; predIndex < block.getPredecessors().size(); predIndex++) {
          com.debughelper.tools.r8.ir.code.BasicBlock pred = block.getPredecessors().get(predIndex);
          if (pred.getInstructions().size() == 1) {
            continue;
          }
          Wrapper<com.debughelper.tools.r8.ir.code.BasicBlock> wrapper = equivalence.wrap(pred);
          if (blockToIndex.containsKey(wrapper)) {
            changed = true;
            int otherPredIndex = blockToIndex.get(wrapper);
            com.debughelper.tools.r8.ir.code.BasicBlock otherPred = block.getPredecessors().get(otherPredIndex);
            pred.clearCatchHandlers();
            pred.getInstructions().clear();
            equivalence.clearComputedHash(pred);
            for (com.debughelper.tools.r8.ir.code.BasicBlock succ : pred.getSuccessors()) {
              succ.removePredecessor(pred);
            }
            pred.getSuccessors().clear();
            pred.getSuccessors().add(otherPred);
            assert !otherPred.getPredecessors().contains(pred);
            otherPred.getPredecessors().add(pred);
            com.debughelper.tools.r8.ir.code.Goto exit = new Goto();
            exit.setBlock(pred);
            pred.getInstructions().add(exit);
          } else {
            blockToIndex.put(wrapper, predIndex);
          }
        }
      }
    } while (changed);
  }

  /**
   * Remove redundant instructions from the code.
   *
   * <p>Currently removes move instructions with the same src and target register and const
   * instructions where the constant is known to be in the register already.
   *
   * @param code the code from which to remove redundant instruction
   * @param allocator the register allocator providing registers for values
   */
  private static void removeRedundantInstructions(
          IRCode code, LinearScanRegisterAllocator allocator) {
    for (BasicBlock block : code.blocks) {
      // Mapping from register number to const number instructions for this basic block.
      // Used to remove redundant const instructions that reloads the same constant into
      // the same register.
      Map<Integer, com.debughelper.tools.r8.ir.code.ConstNumber> registerToNumber = new HashMap<>();
      MoveEliminator moveEliminator = new MoveEliminator(allocator);
      ListIterator<com.debughelper.tools.r8.ir.code.Instruction> iterator = block.getInstructions().listIterator();
      while (iterator.hasNext()) {
        Instruction current = iterator.next();
        if (moveEliminator.shouldBeEliminated(current)) {
          iterator.remove();
        } else if (current.outValue() != null && current.outValue().needsRegister()) {
          Value outValue = current.outValue();
          int instructionNumber = current.getNumber();
          if (outValue.isConstant() && current.isConstNumber()) {
            if (constantSpilledAtDefinition(current.asConstNumber())) {
              // Remove constant instructions that are spilled at their definition and are
              // therefore unused.
              iterator.remove();
              continue;
            }
            int outRegister = allocator.getRegisterForValue(outValue, instructionNumber);
            com.debughelper.tools.r8.ir.code.ConstNumber numberInRegister = registerToNumber.get(outRegister);
            if (numberInRegister != null
                && numberInRegister.identicalNonValueNonPositionParts(current)) {
              // This instruction is not needed, the same constant is already in this register.
              // We don't consider the positions of the two (non-throwing) instructions.
              iterator.remove();
            } else {
              // Insert the current constant in the mapping. Make sure to clobber the second
              // register if wide and register-1 if that defines a wide value.
              registerToNumber.put(outRegister, current.asConstNumber());
              if (current.outType().isWide()) {
                registerToNumber.remove(outRegister + 1);
              } else {
                removeWideConstantCovering(registerToNumber, outRegister);
              }
            }
          } else {
            // This instruction writes registers with a non-constant value. Remove the registers
            // from the mapping.
            int outRegister = allocator.getRegisterForValue(outValue, instructionNumber);
            for (int i = 0; i < outValue.requiredRegisters(); i++) {
              registerToNumber.remove(outRegister + i);
            }
            // Check if the first register written is the second part of a wide value. If so
            // the wide value is no longer active.
            removeWideConstantCovering(registerToNumber, outRegister);
          }
        }
      }
    }
  }

  private static void removeWideConstantCovering(
          Map<Integer, com.debughelper.tools.r8.ir.code.ConstNumber> registerToNumber, int register) {
    com.debughelper.tools.r8.ir.code.ConstNumber number = registerToNumber.get(register - 1);
    if (number != null && number.outType().isWide()) {
      registerToNumber.remove(register - 1);
    }
  }

  private static boolean constantSpilledAtDefinition(ConstNumber constNumber) {
    if (constNumber.outValue().isFixedRegisterValue()) {
      return false;
    }
    LiveIntervals definitionIntervals =
        constNumber.outValue().getLiveIntervals().getSplitCovering(constNumber.getNumber());
    return definitionIntervals.isSpilledAndRematerializable();
  }
}
