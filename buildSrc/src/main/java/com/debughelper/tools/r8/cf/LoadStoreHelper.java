// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.cf;

import com.debughelper.tools.r8.cf.CfRegisterAllocator;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.ConstClass;
import com.debughelper.tools.r8.ir.code.ConstInstruction;
import com.debughelper.tools.r8.ir.code.ConstNumber;
import com.debughelper.tools.r8.ir.code.ConstString;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.Load;
import com.debughelper.tools.r8.ir.code.Phi;
import com.debughelper.tools.r8.ir.code.Pop;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.ir.code.StackValue;
import com.debughelper.tools.r8.ir.code.Store;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.code.ValueType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LoadStoreHelper {

  private final com.debughelper.tools.r8.ir.code.IRCode code;
  private final Map<com.debughelper.tools.r8.ir.code.Value, DexType> types;

  public LoadStoreHelper(IRCode code, Map<com.debughelper.tools.r8.ir.code.Value, DexType> types) {
    this.code = code;
    this.types = types;
  }

  public void insertLoadsAndStores() {
    // Insert per-instruction loads and stores.
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      com.debughelper.tools.r8.ir.code.InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction current = it.next();
        current.insertLoadAndStores(it, this);
      }
    }
  }

  public void insertPhiMoves(CfRegisterAllocator allocator) {
    // Insert phi stores in all predecessors.
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      if (!block.getPhis().isEmpty()) {
        // TODO(zerny): Phi's at an exception block must be dealt with at block entry.
        assert !block.entry().isMoveException();
        for (int predIndex = 0; predIndex < block.getPredecessors().size(); predIndex++) {
          com.debughelper.tools.r8.ir.code.BasicBlock pred = block.getPredecessors().get(predIndex);
          List<com.debughelper.tools.r8.ir.code.Phi> phis = block.getPhis();
          List<PhiMove> moves = new ArrayList<>(phis.size());
          for (com.debughelper.tools.r8.ir.code.Phi phi : phis) {
            com.debughelper.tools.r8.ir.code.Value value = phi.getOperand(predIndex);
            if (allocator.getRegisterForValue(phi) != allocator.getRegisterForValue(value)) {
              moves.add(new PhiMove(phi, value));
            }
          }
          com.debughelper.tools.r8.ir.code.InstructionListIterator it = pred.listIterator(pred.getInstructions().size());
          it.previous();
          movePhis(moves, it);
        }
        allocator.addToLiveAtEntrySet(block, block.getPhis());
      }
    }
    code.blocks.forEach(com.debughelper.tools.r8.ir.code.BasicBlock::clearUserInfo);
  }

  private com.debughelper.tools.r8.ir.code.StackValue createStackValue(com.debughelper.tools.r8.ir.code.Value value, int height) {
    if (value.outType().isObject()) {
      return com.debughelper.tools.r8.ir.code.StackValue.forObjectType(types.get(value), height);
    }
    return com.debughelper.tools.r8.ir.code.StackValue.forNonObjectType(value.outType(), height);
  }

  private com.debughelper.tools.r8.ir.code.StackValue createStackValue(DexType type, int height) {
    if (type.isPrimitiveType()) {
      return com.debughelper.tools.r8.ir.code.StackValue.forNonObjectType(ValueType.fromDexType(type), height);
    }
    return com.debughelper.tools.r8.ir.code.StackValue.forObjectType(type, height);
  }

  public void loadInValues(com.debughelper.tools.r8.ir.code.Instruction instruction, com.debughelper.tools.r8.ir.code.InstructionListIterator it) {
    int topOfStack = 0;
    it.previous();
    for (int i = 0; i < instruction.inValues().size(); i++) {
      com.debughelper.tools.r8.ir.code.Value value = instruction.inValues().get(i);
      com.debughelper.tools.r8.ir.code.StackValue stackValue = createStackValue(value, topOfStack++);
      add(load(stackValue, value), instruction, it);
      value.removeUser(instruction);
      instruction.replaceValue(i, stackValue);
    }
    it.next();
  }

  public void storeOutValue(com.debughelper.tools.r8.ir.code.Instruction instruction, com.debughelper.tools.r8.ir.code.InstructionListIterator it) {
    if (instruction.outValue() instanceof com.debughelper.tools.r8.ir.code.StackValue) {
      assert instruction.isConstInstruction();
      return;
    }
    if (!instruction.outValue().isUsed()) {
      popOutValue(instruction.outValue(), instruction, it);
      return;
    }
    com.debughelper.tools.r8.ir.code.StackValue newOutValue = createStackValue(instruction.outValue(), 0);
    com.debughelper.tools.r8.ir.code.Value oldOutValue = instruction.swapOutValue(newOutValue);
    com.debughelper.tools.r8.ir.code.Store store = new com.debughelper.tools.r8.ir.code.Store(oldOutValue, newOutValue);
    // Move the debugging-locals liveness pertaining to the instruction to the store.
    instruction.moveDebugValues(store);
    add(store, instruction, it);
  }

  public void popOutValue(com.debughelper.tools.r8.ir.code.Value value, com.debughelper.tools.r8.ir.code.Instruction instruction, com.debughelper.tools.r8.ir.code.InstructionListIterator it) {
    com.debughelper.tools.r8.ir.code.StackValue newOutValue = createStackValue(value, 0);
    instruction.swapOutValue(newOutValue);
    add(new com.debughelper.tools.r8.ir.code.Pop(newOutValue), instruction, it);
  }

  public void popOutType(DexType type, com.debughelper.tools.r8.ir.code.Instruction instruction, com.debughelper.tools.r8.ir.code.InstructionListIterator it) {
    com.debughelper.tools.r8.ir.code.StackValue newOutValue = createStackValue(type, 0);
    instruction.swapOutValue(newOutValue);
    add(new Pop(newOutValue), instruction, it);
  }

  private static class PhiMove {
    final com.debughelper.tools.r8.ir.code.Phi phi;
    final com.debughelper.tools.r8.ir.code.Value operand;

    public PhiMove(Phi phi, com.debughelper.tools.r8.ir.code.Value operand) {
      this.phi = phi;
      this.operand = operand;
    }
  }

  private void movePhis(List<PhiMove> moves, com.debughelper.tools.r8.ir.code.InstructionListIterator it) {
    // TODO(zerny): Accounting for non-interfering phis would lower the max stack size.
    int topOfStack = 0;
    List<com.debughelper.tools.r8.ir.code.StackValue> temps = new ArrayList<>(moves.size());
    for (PhiMove move : moves) {
      com.debughelper.tools.r8.ir.code.StackValue tmp = createStackValue(move.phi, topOfStack++);
      add(load(tmp, move.operand), move.phi.getBlock(), com.debughelper.tools.r8.ir.code.Position.none(), it);
      temps.add(tmp);
      move.operand.removePhiUser(move.phi);
    }
    for (int i = moves.size() - 1; i >= 0; i--) {
      PhiMove move = moves.get(i);
      com.debughelper.tools.r8.ir.code.StackValue tmp = temps.get(i);
      FixedLocalValue out = new FixedLocalValue(move.phi);
      add(new Store(out, tmp), move.phi.getBlock(), com.debughelper.tools.r8.ir.code.Position.none(), it);
      move.phi.replaceUsers(out);
    }
  }

  private com.debughelper.tools.r8.ir.code.Instruction load(StackValue stackValue, Value value) {
    if (value.isConstant()) {
      ConstInstruction constant = value.getConstInstruction();
      if (constant.isConstNumber()) {
        return new ConstNumber(stackValue, constant.asConstNumber().getRawValue());
      } else if (constant.isConstString()) {
        return new ConstString(stackValue, constant.asConstString().getValue());
      } else if (constant.isConstClass()) {
        return new ConstClass(stackValue, constant.asConstClass().getValue());
      } else {
        throw new Unreachable("Unexpected constant value: " + value);
      }
    }
    return new Load(stackValue, value);
  }

  private static void add(
          com.debughelper.tools.r8.ir.code.Instruction newInstruction, com.debughelper.tools.r8.ir.code.Instruction existingInstruction, com.debughelper.tools.r8.ir.code.InstructionListIterator it) {
    add(newInstruction, existingInstruction.getBlock(), existingInstruction.getPosition(), it);
  }

  private static void add(
          Instruction newInstruction, BasicBlock block, Position position, InstructionListIterator it) {
    newInstruction.setBlock(block);
    newInstruction.setPosition(position);
    it.add(newInstruction);
  }
}
