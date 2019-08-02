// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.analysis.constant;

import com.debughelper.tools.r8.ir.analysis.constant.LatticeElement;
import com.debughelper.tools.r8.ir.analysis.constant.Top;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.ConstNumber;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.If;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.JumpInstruction;
import com.debughelper.tools.r8.ir.code.Phi;
import com.debughelper.tools.r8.ir.code.Switch;
import com.debughelper.tools.r8.ir.code.Value;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Implementation of Sparse Conditional Constant Propagation from the paper of Wegman and Zadeck
 * "Constant Propagation with Conditional Branches".
 * https://www.cs.utexas.edu/users/lin/cs380c/wegman.pdf
 */
public class SparseConditionalConstantPropagation {

  private final com.debughelper.tools.r8.ir.code.IRCode code;
  private final Map<com.debughelper.tools.r8.ir.code.Value, com.debughelper.tools.r8.ir.analysis.constant.LatticeElement> mapping = new HashMap<>();
  private final Deque<com.debughelper.tools.r8.ir.code.Value> ssaEdges = new LinkedList<>();
  private final Deque<com.debughelper.tools.r8.ir.code.BasicBlock> flowEdges = new LinkedList<>();
  private final int nextBlockNumber;
  private final BitSet[] executableFlowEdges;
  private final BitSet visitedBlocks;

  public SparseConditionalConstantPropagation(IRCode code) {
    this.code = code;
    nextBlockNumber = code.getHighestBlockNumber() + 1;
    executableFlowEdges = new BitSet[nextBlockNumber];
    visitedBlocks = new BitSet(nextBlockNumber);
  }

  public void run() {

    com.debughelper.tools.r8.ir.code.BasicBlock firstBlock = code.blocks.get(0);
    visitInstructions(firstBlock);

    while (!flowEdges.isEmpty() || !ssaEdges.isEmpty()) {
      while (!flowEdges.isEmpty()) {
        com.debughelper.tools.r8.ir.code.BasicBlock block = flowEdges.poll();
        for (com.debughelper.tools.r8.ir.code.Phi phi : block.getPhis()) {
          visitPhi(phi);
        }
        if (!visitedBlocks.get(block.getNumber())) {
          visitInstructions(block);
        }
      }
      while (!ssaEdges.isEmpty()) {
        com.debughelper.tools.r8.ir.code.Value value = ssaEdges.poll();
        for (com.debughelper.tools.r8.ir.code.Phi phi : value.uniquePhiUsers()) {
          visitPhi(phi);
        }
        for (com.debughelper.tools.r8.ir.code.Instruction user : value.uniqueUsers()) {
          com.debughelper.tools.r8.ir.code.BasicBlock userBlock = user.getBlock();
          if (visitedBlocks.get(userBlock.getNumber())) {
            visitInstruction(user);
          }
        }
      }
    }
    rewriteCode();
    assert code.isConsistentSSA();
  }

  private void rewriteCode() {
    List<com.debughelper.tools.r8.ir.code.BasicBlock> blockToAnalyze = new ArrayList<>();

    mapping.entrySet().stream()
        .filter((entry) -> entry.getValue().isConst())
        .forEach((entry) -> {
          com.debughelper.tools.r8.ir.code.Value value = entry.getKey();
          com.debughelper.tools.r8.ir.code.ConstNumber evaluatedConst = entry.getValue().asConst().getConstNumber();
          if (value.definition != evaluatedConst) {
            if (value.isPhi()) {
              // D8Adapter relies on dead code removal to get rid of the dead phi itself.
              if (value.numberOfAllUsers() != 0) {
                com.debughelper.tools.r8.ir.code.BasicBlock block = value.asPhi().getBlock();
                blockToAnalyze.add(block);
                // Create a new constant, because it can be an existing constant that flow directly
                // into the phi.
                com.debughelper.tools.r8.ir.code.ConstNumber newConst = com.debughelper.tools.r8.ir.code.ConstNumber.copyOf(code, evaluatedConst);
                com.debughelper.tools.r8.ir.code.InstructionListIterator iterator = block.listIterator();
                com.debughelper.tools.r8.ir.code.Instruction inst = iterator.nextUntil((i) -> !i.isMoveException());
                newConst.setPosition(inst.getPosition());
                if (!inst.isDebugPosition()) {
                  iterator.previous();
                }
                iterator.add(newConst);
                value.replaceUsers(newConst.outValue());
              }
            } else {
              com.debughelper.tools.r8.ir.code.BasicBlock block = value.definition.getBlock();
              InstructionListIterator iterator = block.listIterator();
              com.debughelper.tools.r8.ir.code.Instruction toReplace = iterator.nextUntil((i) -> i == value.definition);
              iterator.replaceCurrentInstruction(evaluatedConst);
            }
          }
        });

    for (com.debughelper.tools.r8.ir.code.BasicBlock block : blockToAnalyze) {
      block.deduplicatePhis();
    }

    code.removeAllTrivialPhis();
  }

  private com.debughelper.tools.r8.ir.analysis.constant.LatticeElement getLatticeElement(com.debughelper.tools.r8.ir.code.Value value) {
    return mapping.getOrDefault(value, com.debughelper.tools.r8.ir.analysis.constant.Top.getInstance());
  }

  private void setLatticeElement(Value value, com.debughelper.tools.r8.ir.analysis.constant.LatticeElement element) {
    mapping.put(value, element);
  }

  private void visitPhi(Phi phi) {
    com.debughelper.tools.r8.ir.code.BasicBlock phiBlock = phi.getBlock();
    int phiBlockNumber = phiBlock.getNumber();
    com.debughelper.tools.r8.ir.analysis.constant.LatticeElement element = Top.getInstance();
    List<com.debughelper.tools.r8.ir.code.BasicBlock> predecessors = phiBlock.getPredecessors();
    int size = predecessors.size();
    for (int i = 0; i < size; i++) {
      com.debughelper.tools.r8.ir.code.BasicBlock predecessor = predecessors.get(i);
      if (isExecutableEdge(predecessor.getNumber(), phiBlockNumber)) {
        element = element.meet(getLatticeElement(phi.getOperand(i)));
        // bottom lattice can no longer be changed, thus no need to continue
        if (element.isBottom()) {
          break;
        }
      }
    }
    if (!element.isTop()) {
      com.debughelper.tools.r8.ir.analysis.constant.LatticeElement currentPhiElement = getLatticeElement(phi);
      if (currentPhiElement.meet(element) != currentPhiElement) {
        ssaEdges.add(phi);
        setLatticeElement(phi, element);
      }
    }
  }

  private void visitInstructions(com.debughelper.tools.r8.ir.code.BasicBlock block) {
    for (com.debughelper.tools.r8.ir.code.Instruction instruction : block.getInstructions()) {
      visitInstruction(instruction);
    }
    visitedBlocks.set(block.getNumber());
  }

  private void visitInstruction(Instruction instruction) {
    if (instruction.outValue() != null && !instruction.isDebugLocalUninitialized()) {
      com.debughelper.tools.r8.ir.analysis.constant.LatticeElement element = instruction.evaluate(code, this::getLatticeElement);
      com.debughelper.tools.r8.ir.analysis.constant.LatticeElement currentLattice = getLatticeElement(instruction.outValue());
      if (currentLattice.meet(element) != currentLattice) {
        setLatticeElement(instruction.outValue(), element);
        ssaEdges.add(instruction.outValue());
      }
    }
    if (instruction.isJumpInstruction()) {
      addFlowEdgesForJumpInstruction(instruction.asJumpInstruction());
    }
  }

  private void addFlowEdgesForJumpInstruction(JumpInstruction jumpInstruction) {
    com.debughelper.tools.r8.ir.code.BasicBlock jumpInstBlock = jumpInstruction.getBlock();
    int jumpInstBlockNumber = jumpInstBlock.getNumber();
    if (jumpInstruction.isIf()) {
      If theIf = jumpInstruction.asIf();
      if (theIf.isZeroTest()) {
        com.debughelper.tools.r8.ir.analysis.constant.LatticeElement element = getLatticeElement(theIf.inValues().get(0));
        if (element.isConst()) {
          com.debughelper.tools.r8.ir.code.BasicBlock target = theIf.targetFromCondition(element.asConst().getConstNumber());
          if (!isExecutableEdge(jumpInstBlockNumber, target.getNumber())) {
            setExecutableEdge(jumpInstBlockNumber, target.getNumber());
            flowEdges.add(target);
          }
          return;
        }
      } else {
        com.debughelper.tools.r8.ir.analysis.constant.LatticeElement leftElement = getLatticeElement(theIf.inValues().get(0));
        com.debughelper.tools.r8.ir.analysis.constant.LatticeElement rightElement = getLatticeElement(theIf.inValues().get(1));
        if (leftElement.isConst() && rightElement.isConst()) {
          com.debughelper.tools.r8.ir.code.ConstNumber leftNumber = leftElement.asConst().getConstNumber();
          ConstNumber rightNumber = rightElement.asConst().getConstNumber();
          com.debughelper.tools.r8.ir.code.BasicBlock target = theIf.targetFromCondition(leftNumber, rightNumber);
          if (!isExecutableEdge(jumpInstBlockNumber, target.getNumber())) {
            setExecutableEdge(jumpInstBlockNumber, target.getNumber());
            flowEdges.add(target);
          }
          return;
        }
        assert !leftElement.isTop();
        assert !rightElement.isTop();
      }
    } else if (jumpInstruction.isSwitch()) {
      Switch switchInst = jumpInstruction.asSwitch();
      LatticeElement switchElement = getLatticeElement(switchInst.value());
      if (switchElement.isConst()) {
        com.debughelper.tools.r8.ir.code.BasicBlock target = switchInst.getKeyToTargetMap()
            .get(switchElement.asConst().getIntValue());
        if (target == null) {
          target = switchInst.fallthroughBlock();
        }
        assert target != null;
        setExecutableEdge(jumpInstBlockNumber, target.getNumber());
        flowEdges.add(target);
        return;
      }
    }

    for (BasicBlock dst : jumpInstBlock.getSuccessors()) {
      if (!isExecutableEdge(jumpInstBlockNumber, dst.getNumber())) {
        setExecutableEdge(jumpInstBlockNumber, dst.getNumber());
        flowEdges.add(dst);
      }
    }
  }

  private void setExecutableEdge(int from, int to) {
    BitSet previousExecutable = executableFlowEdges[to];
    if (previousExecutable == null) {
      previousExecutable = new BitSet(nextBlockNumber);
      executableFlowEdges[to] = previousExecutable;
    }
    previousExecutable.set(from);
  }

  private boolean isExecutableEdge(int from, int to) {
    BitSet previousExecutable = executableFlowEdges[to];
    if (previousExecutable == null) {
      return false;
    }
    return previousExecutable.get(from);
  }
}
