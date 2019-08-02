// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.conversion;

import com.debughelper.tools.r8.cf.code.CfFrame;
import com.debughelper.tools.r8.cf.code.CfFrame.FrameType;
import com.debughelper.tools.r8.graph.CfCode;
import com.debughelper.tools.r8.graph.CfCode.LocalVariableInfo;
import com.debughelper.tools.r8.ir.conversion.DexBuilder;
import com.debughelper.tools.r8.ir.optimize.CodeRewriter;
import com.debughelper.tools.r8.ir.optimize.DeadCodeRemover;
import com.debughelper.tools.r8.cf.CfRegisterAllocator;
import com.debughelper.tools.r8.cf.LoadStoreHelper;
import com.debughelper.tools.r8.cf.TypeVerificationHelper;
import com.debughelper.tools.r8.cf.code.CfInstruction;
import com.debughelper.tools.r8.cf.code.CfLabel;
import com.debughelper.tools.r8.cf.code.CfPosition;
import com.debughelper.tools.r8.cf.code.CfTryCatch;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.AppInfoWithSubtyping;
import com.debughelper.tools.r8.graph.DebugLocalInfo;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.GraphLense;
import com.debughelper.tools.r8.ir.code.Argument;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.CatchHandlers;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionIterator;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.InvokeDirect;
import com.debughelper.tools.r8.ir.code.JumpInstruction;
import com.debughelper.tools.r8.ir.code.Load;
import com.debughelper.tools.r8.ir.code.NewInstance;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.ir.code.StackValue;
import com.debughelper.tools.r8.ir.code.Store;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.utils.InternalOptions;

import it.unimi.dsi.fastutil.ints.Int2ReferenceAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap.Entry;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

public class CfBuilder {

  private final com.debughelper.tools.r8.graph.DexItemFactory factory;
  private final com.debughelper.tools.r8.graph.DexEncodedMethod method;
  private final com.debughelper.tools.r8.ir.code.IRCode code;

  private Map<com.debughelper.tools.r8.ir.code.Value, DexType> types;
  private Map<com.debughelper.tools.r8.ir.code.BasicBlock, com.debughelper.tools.r8.cf.code.CfLabel> labels;
  private Set<com.debughelper.tools.r8.cf.code.CfLabel> emittedLabels;
  private List<com.debughelper.tools.r8.cf.code.CfInstruction> instructions;
  private com.debughelper.tools.r8.cf.CfRegisterAllocator registerAllocator;

  private com.debughelper.tools.r8.ir.code.Position currentPosition = com.debughelper.tools.r8.ir.code.Position.none();

  private final Int2ReferenceMap<com.debughelper.tools.r8.graph.DebugLocalInfo> emittedLocals = new Int2ReferenceOpenHashMap<>();
  private Int2ReferenceMap<com.debughelper.tools.r8.graph.DebugLocalInfo> pendingLocals = null;
  private boolean pendingLocalChanges = false;

  private final List<com.debughelper.tools.r8.graph.CfCode.LocalVariableInfo> localVariablesTable = new ArrayList<>();
  private final Int2ReferenceMap<com.debughelper.tools.r8.graph.CfCode.LocalVariableInfo> openLocalVariables =
      new Int2ReferenceOpenHashMap<>();

  private com.debughelper.tools.r8.graph.AppInfoWithSubtyping appInfo;

  private Map<com.debughelper.tools.r8.ir.code.NewInstance, List<com.debughelper.tools.r8.ir.code.InvokeDirect>> initializers;
  private List<com.debughelper.tools.r8.ir.code.InvokeDirect> thisInitializers;
  private Map<NewInstance, com.debughelper.tools.r8.cf.code.CfLabel> newInstanceLabels;

  // Internal abstraction of the stack values and height.
  private static class Stack {
    int maxHeight = 0;
    int height = 0;

    boolean isEmpty() {
      return height == 0;
    }

    void push(com.debughelper.tools.r8.ir.code.Value value) {
      assert value instanceof com.debughelper.tools.r8.ir.code.StackValue;
      height += value.requiredRegisters();
      maxHeight = Math.max(maxHeight, height);
    }

    void pop(com.debughelper.tools.r8.ir.code.Value value) {
      assert value instanceof com.debughelper.tools.r8.ir.code.StackValue;
      height -= value.requiredRegisters();
    }
  }

  public CfBuilder(DexEncodedMethod method, IRCode code, com.debughelper.tools.r8.graph.DexItemFactory factory) {
    this.method = method;
    this.code = code;
    this.factory = factory;
  }

  public DexItemFactory getFactory() {
    return factory;
  }

  public com.debughelper.tools.r8.graph.CfCode build(
      CodeRewriter rewriter,
      GraphLense graphLense,
      InternalOptions options,
      AppInfoWithSubtyping appInfo) {
    computeInitializers();
    types = new TypeVerificationHelper(code, factory, appInfo).computeVerificationTypes();
    splitExceptionalBlocks();
    com.debughelper.tools.r8.cf.LoadStoreHelper loadStoreHelper = new LoadStoreHelper(code, types);
    loadStoreHelper.insertLoadsAndStores();
    DeadCodeRemover.removeDeadCode(code, rewriter, graphLense, options);
    removeUnneededLoadsAndStores();
    registerAllocator = new CfRegisterAllocator(code, options);
    registerAllocator.allocateRegisters();
    loadStoreHelper.insertPhiMoves(registerAllocator);
    CodeRewriter.collapsTrivialGotos(method, code);
    int instructionTableCount =
        com.debughelper.tools.r8.ir.conversion.DexBuilder.instructionNumberToIndex(code.numberRemainingInstructions());
    DexBuilder.removeRedundantDebugPositions(code, instructionTableCount);
    this.appInfo = appInfo;
    com.debughelper.tools.r8.graph.CfCode code = buildCfCode();
    return code;
  }

  public com.debughelper.tools.r8.graph.DexField resolveField(DexField field) {
    DexEncodedField resolvedField = appInfo.resolveFieldOn(field.clazz, field);
    return resolvedField == null ? field : resolvedField.field;
  }

  private void computeInitializers() {
    assert initializers == null;
    assert thisInitializers == null;
    initializers = new HashMap<>();
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      for (com.debughelper.tools.r8.ir.code.Instruction insn : block.getInstructions()) {
        if (insn.isNewInstance()) {
          initializers.put(insn.asNewInstance(), computeInitializers(insn.outValue()));
        } else if (insn.isArgument() && method.isInstanceInitializer()) {
          if (insn.outValue().isThis()) {
            // By JVM8 §4.10.1.9 (invokespecial), a this() or super() call in a constructor
            // changes the type of `this` from uninitializedThis
            // to the type of the class of the <init> method.
            thisInitializers = computeInitializers(insn.outValue());
          }
        }
      }
    }
    assert !(method.isInstanceInitializer() && thisInitializers == null);
  }

  private List<com.debughelper.tools.r8.ir.code.InvokeDirect> computeInitializers(com.debughelper.tools.r8.ir.code.Value value) {
    List<com.debughelper.tools.r8.ir.code.InvokeDirect> initializers = new ArrayList<>();
    for (com.debughelper.tools.r8.ir.code.Instruction user : value.uniqueUsers()) {
      if (user instanceof com.debughelper.tools.r8.ir.code.InvokeDirect
          && user.inValues().get(0) == value
          && user.asInvokeDirect().getInvokedMethod().name == factory.constructorMethodName) {
        initializers.add(user.asInvokeDirect());
      }
    }
    return initializers;
  }

  // Split all blocks with throwing instructions and exceptional edges such that any non-throwing
  // instructions that might define values prior to the throwing exception are excluded from the
  // try-catch range. Failure to do so will result in code that does not verify on the JVM.
  private void splitExceptionalBlocks() {
    ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> it = code.listIterator();
    while (it.hasNext()) {
      com.debughelper.tools.r8.ir.code.BasicBlock block = it.next();
      if (!block.hasCatchHandlers()) {
        continue;
      }
      int size = block.getInstructions().size();
      boolean isThrow = block.exit().isThrow();
      if ((isThrow && size == 1) || (!isThrow && size == 2)) {
        // Fast-path to avoid processing blocks with just a single throwing instruction.
        continue;
      }
      com.debughelper.tools.r8.ir.code.InstructionListIterator instructions = block.listIterator();
      boolean hasOutValues = false;
      while (instructions.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = instructions.next();
        if (instruction.instructionTypeCanThrow()) {
          break;
        }
        hasOutValues |= instruction.outValue() != null;
      }
      if (hasOutValues) {
        instructions.previous();
        instructions.split(code, it);
      }
    }
  }

  private void removeUnneededLoadsAndStores() {
    Iterator<com.debughelper.tools.r8.ir.code.BasicBlock> blockIterator = code.listIterator();
    while (blockIterator.hasNext()) {
      com.debughelper.tools.r8.ir.code.BasicBlock block = blockIterator.next();
      InstructionListIterator it = block.listIterator();
      while (it.hasNext()) {
        com.debughelper.tools.r8.ir.code.Instruction store = it.next();
        // Eliminate unneeded loads of stores:
        //  v <- store si
        //  si <- load v
        // where |users(v)| == 1 (ie, the load is the only user)
        if (store instanceof Store && store.outValue().numberOfAllUsers() == 1) {
          com.debughelper.tools.r8.ir.code.Instruction load = it.peekNext();
          if (load instanceof Load && load.inValues().get(0) == store.outValue()) {
            com.debughelper.tools.r8.ir.code.Value storeIn = store.inValues().get(0);
            com.debughelper.tools.r8.ir.code.Value loadOut = load.outValue();
            loadOut.replaceUsers(storeIn);
            storeIn.removeUser(store);
            store.outValue().removeUser(load);
            // Remove the store.
            it.previous();
            it.removeOrReplaceByDebugLocalRead();
            // Remove the load.
            it.next();
            it.remove();
            // Rewind to the instruction before the store so we can identify new patterns.
            it.previous();
          }
        }
      }
    }
  }

  private com.debughelper.tools.r8.graph.CfCode buildCfCode() {
    Stack stack = new Stack();
    List<com.debughelper.tools.r8.cf.code.CfTryCatch> tryCatchRanges = new ArrayList<>();
    labels = new HashMap<>(code.blocks.size());
    emittedLabels = new HashSet<>(code.blocks.size());
    newInstanceLabels = new HashMap<>(initializers.size());
    instructions = new ArrayList<>();
    ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blockIterator = code.listIterator();
    com.debughelper.tools.r8.ir.code.BasicBlock block = blockIterator.next();
    com.debughelper.tools.r8.cf.code.CfLabel tryCatchStart = null;
    com.debughelper.tools.r8.ir.code.CatchHandlers<com.debughelper.tools.r8.ir.code.BasicBlock> tryCatchHandlers = com.debughelper.tools.r8.ir.code.CatchHandlers.EMPTY_BASIC_BLOCK;
    com.debughelper.tools.r8.ir.code.BasicBlock pendingFrame = null;
    boolean previousFallthrough = false;
    do {
      assert stack.isEmpty();
      CatchHandlers<com.debughelper.tools.r8.ir.code.BasicBlock> handlers = block.getCatchHandlers();
      if (!tryCatchHandlers.equals(handlers)) {
        if (!tryCatchHandlers.isEmpty()) {
          // Close try-catch and save the range.
          com.debughelper.tools.r8.cf.code.CfLabel tryCatchEnd = getLabel(block);
          tryCatchRanges.add(
              CfTryCatch.fromBuilder(tryCatchStart, tryCatchEnd, tryCatchHandlers, this));
          emitLabel(tryCatchEnd);
        }
        if (!handlers.isEmpty()) {
          // Open a try-catch.
          tryCatchStart = getLabel(block);
          emitLabel(tryCatchStart);
        }
        tryCatchHandlers = handlers;
      }
      com.debughelper.tools.r8.ir.code.BasicBlock nextBlock = blockIterator.hasNext() ? blockIterator.next() : null;
      // If previousBlock is fallthrough, then it is counted in getPredecessors().size(), but
      // we only want to set a pendingFrame if we have a predecessor which is not previousBlock.
      if (block.getPredecessors().size() > (previousFallthrough ? 1 : 0)) {
        pendingFrame = block;
        emitLabel(getLabel(block));
      }
      if (pendingFrame != null) {
        boolean advancesPC = hasMaterializingInstructions(block, nextBlock);
        // If block has no materializing instructions, then we postpone emitting the frame
        // until the next block. In this case, nextBlock must be non-null
        // (or we would fall off the edge of the method).
        assert advancesPC || nextBlock != null;
        if (advancesPC) {
          addFrame(pendingFrame, Collections.emptyList());
          pendingFrame = null;
        }
      }
      JumpInstruction exit = block.exit();
      boolean fallthrough =
          (exit.isGoto() && exit.asGoto().getTarget() == nextBlock)
              || (exit.isIf() && exit.fallthroughBlock() == nextBlock);
      Int2ReferenceMap<com.debughelper.tools.r8.graph.DebugLocalInfo> locals = block.getLocalsAtEntry();
      if (locals == null) {
        assert pendingLocals == null;
      } else {
        pendingLocals = new Int2ReferenceOpenHashMap<>(locals);
        pendingLocalChanges = true;
      }
      buildCfInstructions(block, fallthrough, stack);
      block = nextBlock;
      previousFallthrough = fallthrough;
    } while (block != null);
    assert stack.isEmpty();
    com.debughelper.tools.r8.cf.code.CfLabel endLabel = ensureLabel();
    for (com.debughelper.tools.r8.graph.CfCode.LocalVariableInfo info : openLocalVariables.values()) {
      info.setEnd(endLabel);
      localVariablesTable.add(info);
    }
    return new com.debughelper.tools.r8.graph.CfCode(
        method.method,
        stack.maxHeight,
        registerAllocator.registersUsed(),
        instructions,
        tryCatchRanges,
        localVariablesTable);
  }

  private static boolean isNopInstruction(com.debughelper.tools.r8.ir.code.Instruction instruction, com.debughelper.tools.r8.ir.code.BasicBlock nextBlock) {
    // From DexBuilder
    return instruction.isArgument()
        || instruction.isDebugLocalsChange()
        || (instruction.isGoto() && instruction.asGoto().getTarget() == nextBlock);
  }

  private boolean hasMaterializingInstructions(com.debughelper.tools.r8.ir.code.BasicBlock block, com.debughelper.tools.r8.ir.code.BasicBlock nextBlock) {
    if (block == null) {
      return false;
    }
    for (com.debughelper.tools.r8.ir.code.Instruction instruction : block.getInstructions()) {
      if (!isNopInstruction(instruction, nextBlock)) {
        return true;
      }
    }
    return false;
  }

  private void buildCfInstructions(com.debughelper.tools.r8.ir.code.BasicBlock block, boolean fallthrough, Stack stack) {
    InstructionIterator it = block.iterator();
    while (it.hasNext()) {
      com.debughelper.tools.r8.ir.code.Instruction instruction = it.next();
      if (fallthrough && instruction.isGoto()) {
        assert block.exit() == instruction;
        return;
      }
      for (int i = instruction.inValues().size() - 1; i >= 0; i--) {
        if (instruction.inValues().get(i) instanceof com.debughelper.tools.r8.ir.code.StackValue) {
          stack.pop(instruction.inValues().get(i));
        }
      }
      if (instruction.outValue() != null) {
        com.debughelper.tools.r8.ir.code.Value outValue = instruction.outValue();
        if (outValue instanceof com.debughelper.tools.r8.ir.code.StackValue) {
          stack.push(outValue);
        }
      }
      if (instruction.isDebugLocalsChange()) {
        if (instruction.asDebugLocalsChange().apply(pendingLocals)) {
          pendingLocalChanges = true;
        }
      } else {
        if (instruction.isNewInstance()) {
          newInstanceLabels.put(instruction.asNewInstance(), ensureLabel());
        }
        updatePositionAndLocals(instruction);
        instruction.buildCf(this);
      }
    }
  }

  private void updatePositionAndLocals(com.debughelper.tools.r8.ir.code.Instruction instruction) {
    Position position = instruction.getPosition();
    boolean didLocalsChange = localsChanged();
    boolean didPositionChange = position.isSome() && position != currentPosition;
    if (!didLocalsChange && !didPositionChange) {
      return;
    }
    com.debughelper.tools.r8.cf.code.CfLabel label = ensureLabel();
    if (didLocalsChange) {
      Int2ReferenceSortedMap<com.debughelper.tools.r8.graph.DebugLocalInfo> ending =
          com.debughelper.tools.r8.graph.DebugLocalInfo.endingLocals(emittedLocals, pendingLocals);
      Int2ReferenceSortedMap<com.debughelper.tools.r8.graph.DebugLocalInfo> starting =
          com.debughelper.tools.r8.graph.DebugLocalInfo.startingLocals(emittedLocals, pendingLocals);
      assert !ending.isEmpty() || !starting.isEmpty();
      for (Entry<com.debughelper.tools.r8.graph.DebugLocalInfo> entry : ending.int2ReferenceEntrySet()) {
        int localIndex = entry.getIntKey();
        com.debughelper.tools.r8.graph.CfCode.LocalVariableInfo info = openLocalVariables.remove(localIndex);
        info.setEnd(label);
        localVariablesTable.add(info);
        com.debughelper.tools.r8.graph.DebugLocalInfo removed = emittedLocals.remove(localIndex);
        assert removed == entry.getValue();
      }
      if (!starting.isEmpty()) {
        for (Entry<com.debughelper.tools.r8.graph.DebugLocalInfo> entry : starting.int2ReferenceEntrySet()) {
          int localIndex = entry.getIntKey();
          assert !emittedLocals.containsKey(localIndex);
          assert !openLocalVariables.containsKey(localIndex);
          openLocalVariables.put(
              localIndex, new com.debughelper.tools.r8.graph.CfCode.LocalVariableInfo(localIndex, entry.getValue(), label));
          emittedLocals.put(localIndex, entry.getValue());
        }
      }
      pendingLocalChanges = false;
    }
    if (didPositionChange) {
      add(new CfPosition(label, position));
      currentPosition = position;
    }
  }

  private boolean localsChanged() {
    if (!pendingLocalChanges) {
      return false;
    }
    pendingLocalChanges = !DebugLocalInfo.localsInfoMapsEqual(emittedLocals, pendingLocals);
    return pendingLocalChanges;
  }

  private com.debughelper.tools.r8.cf.code.CfLabel ensureLabel() {
    com.debughelper.tools.r8.cf.code.CfInstruction last = getLastInstruction();
    if (last instanceof com.debughelper.tools.r8.cf.code.CfLabel) {
      return (com.debughelper.tools.r8.cf.code.CfLabel) last;
    }
    com.debughelper.tools.r8.cf.code.CfLabel label = new com.debughelper.tools.r8.cf.code.CfLabel();
    add(label);
    return label;
  }

  private com.debughelper.tools.r8.cf.code.CfInstruction getLastInstruction() {
    return instructions.isEmpty() ? null : instructions.get(instructions.size() - 1);
  }

  private void addFrame(com.debughelper.tools.r8.ir.code.BasicBlock block, Collection<com.debughelper.tools.r8.ir.code.StackValue> stack) {
    // TODO(zerny): Support having values on the stack on control-edges.
    assert stack.isEmpty();

    List<com.debughelper.tools.r8.cf.code.CfFrame.FrameType> stackTypes;
    if (block.entry().isMoveException()) {
      com.debughelper.tools.r8.ir.code.StackValue exception = (StackValue) block.entry().outValue();
      stackTypes = Collections.singletonList(com.debughelper.tools.r8.cf.code.CfFrame.FrameType.initialized(exception.getObjectType()));
    } else {
      stackTypes = Collections.emptyList();
    }

    Collection<com.debughelper.tools.r8.ir.code.Value> locals = registerAllocator.getLocalsAtBlockEntry(block);
    Int2ReferenceSortedMap<com.debughelper.tools.r8.cf.code.CfFrame.FrameType> mapping = new Int2ReferenceAVLTreeMap<>();

    for (com.debughelper.tools.r8.ir.code.Value local : locals) {
      mapping.put(getLocalRegister(local), getFrameType(block, local));
    }
    instructions.add(new com.debughelper.tools.r8.cf.code.CfFrame(mapping, stackTypes));
  }

  private com.debughelper.tools.r8.cf.code.CfFrame.FrameType getFrameType(com.debughelper.tools.r8.ir.code.BasicBlock liveBlock, com.debughelper.tools.r8.ir.code.Value local) {
    switch (local.outType()) {
      case INT:
        return com.debughelper.tools.r8.cf.code.CfFrame.FrameType.initialized(factory.intType);
      case FLOAT:
        return com.debughelper.tools.r8.cf.code.CfFrame.FrameType.initialized(factory.floatType);
      case LONG:
        return com.debughelper.tools.r8.cf.code.CfFrame.FrameType.initialized(factory.longType);
      case DOUBLE:
        return com.debughelper.tools.r8.cf.code.CfFrame.FrameType.initialized(factory.doubleType);
      case OBJECT:
        com.debughelper.tools.r8.cf.code.CfFrame.FrameType type = findAllocator(liveBlock, local);
        return type != null ? type : com.debughelper.tools.r8.cf.code.CfFrame.FrameType.initialized(types.get(local));
      default:
        throw new Unreachable(
            "Unexpected local type: " + local.outType() + " for local: " + local);
    }
  }

  private com.debughelper.tools.r8.cf.code.CfFrame.FrameType findAllocator(com.debughelper.tools.r8.ir.code.BasicBlock liveBlock, com.debughelper.tools.r8.ir.code.Value value) {
    Instruction definition = value.definition;
    while (definition != null && (definition.isStore() || definition.isLoad())) {
      definition = definition.inValues().get(0).definition;
    }
    if (definition == null) {
      return null;
    }
    com.debughelper.tools.r8.cf.code.CfFrame.FrameType res;
    if (definition.isNewInstance()) {
      res = com.debughelper.tools.r8.cf.code.CfFrame.FrameType.uninitializedNew(newInstanceLabels.get(definition.asNewInstance()));
    } else if (definition.isArgument()
        && method.isInstanceInitializer()
        && definition.outValue().isThis()) {
      res = com.debughelper.tools.r8.cf.code.CfFrame.FrameType.uninitializedThis();
    } else {
      return null;
    }
    com.debughelper.tools.r8.ir.code.BasicBlock definitionBlock = definition.getBlock();
    Set<com.debughelper.tools.r8.ir.code.BasicBlock> visited = new HashSet<>();
    Deque<com.debughelper.tools.r8.ir.code.BasicBlock> toVisit = new ArrayDeque<>();
    List<com.debughelper.tools.r8.ir.code.InvokeDirect> valueInitializers =
        definition.isArgument() ? thisInitializers : initializers.get(definition.asNewInstance());
    for (InvokeDirect initializer : valueInitializers) {
      com.debughelper.tools.r8.ir.code.BasicBlock initializerBlock = initializer.getBlock();
      if (initializerBlock == liveBlock) {
        return res;
      }
      if (initializerBlock != definitionBlock && visited.add(initializerBlock)) {
        toVisit.addLast(initializerBlock);
      }
    }
    while (!toVisit.isEmpty()) {
      com.debughelper.tools.r8.ir.code.BasicBlock block = toVisit.removeLast();
      for (com.debughelper.tools.r8.ir.code.BasicBlock predecessor : block.getPredecessors()) {
        if (predecessor == liveBlock) {
          return res;
        }
        if (predecessor != definitionBlock && visited.add(predecessor)) {
          toVisit.addLast(predecessor);
        }
      }
    }
    return null;
  }

  private void emitLabel(com.debughelper.tools.r8.cf.code.CfLabel label) {
    if (!emittedLabels.contains(label)) {
      emittedLabels.add(label);
      instructions.add(label);
    }
  }

  // Callbacks

  public com.debughelper.tools.r8.cf.code.CfLabel getLabel(BasicBlock target) {
    return labels.computeIfAbsent(target, (block) -> new CfLabel());
  }

  public int getLocalRegister(Value value) {
    return registerAllocator.getRegisterForValue(value);
  }

  public void add(CfInstruction instruction) {
    instructions.add(instruction);
  }

  public void addArgument(Argument argument) {
    // Nothing so far.
  }
}
