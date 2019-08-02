// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.synthetic;

import static com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.NO_THROW;

import com.debughelper.tools.r8.ir.conversion.SourceCode;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.DebugLocalInfo;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.code.Argument;
import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.CatchHandlers;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public abstract class SyntheticSourceCode implements SourceCode {
  protected final static Predicate<com.debughelper.tools.r8.ir.conversion.IRBuilder> doesNotEndBlock = x -> false;
  protected final static Predicate<com.debughelper.tools.r8.ir.conversion.IRBuilder> endsBlock = x -> true;

  protected final com.debughelper.tools.r8.graph.DexType receiver;
  protected final com.debughelper.tools.r8.graph.DexProto proto;

  // The next free register, note that we always
  // assign each value a new (next available) register.
  private int nextRegister = 0;

  // Registers for receiver and parameters
  private final int receiverRegister;
  private int[] paramRegisters;
  // Values representing receiver and parameters will be filled in
  // buildPrelude() and should only be accessed via appropriate methods
  private com.debughelper.tools.r8.ir.code.Value receiverValue;
  private com.debughelper.tools.r8.ir.code.Value[] paramValues;

  // Instruction constructors
  private List<Consumer<com.debughelper.tools.r8.ir.conversion.IRBuilder>> constructors = new ArrayList<>();
  private List<Predicate<com.debughelper.tools.r8.ir.conversion.IRBuilder>> traceEvents = new ArrayList<>();

  protected SyntheticSourceCode(com.debughelper.tools.r8.graph.DexType receiver, DexProto proto) {
    assert proto != null;
    this.receiver = receiver;
    this.proto = proto;

    // Initialize register values for receiver and arguments
    this.receiverRegister = receiver != null ? nextRegister(com.debughelper.tools.r8.ir.code.ValueType.OBJECT) : -1;

    com.debughelper.tools.r8.graph.DexType[] params = proto.parameters.values;
    int paramCount = params.length;
    this.paramRegisters = new int[paramCount];
    this.paramValues = new com.debughelper.tools.r8.ir.code.Value[paramCount];
    for (int i = 0; i < paramCount; i++) {
      this.paramRegisters[i] = nextRegister(com.debughelper.tools.r8.ir.code.ValueType.fromDexType(params[i]));
    }
  }

  protected final void add(Consumer<com.debughelper.tools.r8.ir.conversion.IRBuilder> constructor) {
    add(constructor, doesNotEndBlock);
  }

  protected final void add(Consumer<com.debughelper.tools.r8.ir.conversion.IRBuilder> constructor, Predicate<com.debughelper.tools.r8.ir.conversion.IRBuilder> traceEvent) {
    constructors.add(constructor);
    traceEvents.add(traceEvent);
  }

  protected final int nextRegister(com.debughelper.tools.r8.ir.code.ValueType type) {
    int value = nextRegister;
    nextRegister += type.requiredRegisters();
    return value;
  }

  protected final com.debughelper.tools.r8.ir.code.Value getReceiverValue() {
    assert receiver != null;
    assert receiverValue != null;
    return receiverValue;
  }

  protected final int getReceiverRegister() {
    assert receiver != null;
    assert receiverRegister >= 0;
    return receiverRegister;
  }

  protected final com.debughelper.tools.r8.ir.code.Value getParamValue(int paramIndex) {
    assert paramIndex >= 0;
    assert paramIndex < paramValues.length;
    return paramValues[paramIndex];
  }

  protected final int getParamCount() {
    return paramValues.length;
  }

  protected final int getParamRegister(int paramIndex) {
    assert paramIndex >= 0;
    assert paramIndex < paramRegisters.length;
    return paramRegisters[paramIndex];
  }

  protected abstract void prepareInstructions();

  @Override
  public final int instructionCount() {
    return constructors.size();
  }

  protected final int lastInstructionIndex() {
    return constructors.size() - 1;
  }

  protected final int nextInstructionIndex() {
    return constructors.size();
  }

  @Override
  public final int instructionIndex(int instructionOffset) {
    return instructionOffset;
  }

  @Override
  public final int instructionOffset(int instructionIndex) {
    return instructionIndex;
  }

  @Override
  public com.debughelper.tools.r8.graph.DebugLocalInfo getIncomingLocal(int register) {
    return null;
  }

  @Override
  public com.debughelper.tools.r8.graph.DebugLocalInfo getOutgoingLocal(int register) {
    return null;
  }

  @Override
  public final int traceInstruction(int instructionIndex, com.debughelper.tools.r8.ir.conversion.IRBuilder builder) {
    return (traceEvents.get(instructionIndex).test(builder) ||
        (instructionIndex == constructors.size() - 1)) ? instructionIndex : -1;
  }

  @Override
  public final void setUp() {
    assert constructors.isEmpty();
    prepareInstructions();
    assert !constructors.isEmpty();
  }

  @Override
  public final void clear() {
    constructors = null;
    traceEvents = null;
    paramRegisters = null;
    paramValues = null;
    receiverValue = null;
  }

  @Override
  public final void buildPrelude(com.debughelper.tools.r8.ir.conversion.IRBuilder builder) {
    if (receiver != null) {
      receiverValue = builder.writeRegister(receiverRegister, com.debughelper.tools.r8.ir.code.ValueType.OBJECT, BasicBlock.ThrowingInfo.NO_THROW);
      builder.add(new com.debughelper.tools.r8.ir.code.Argument(receiverValue));
      receiverValue.markAsThis();
    }

    // Fill in the Argument instructions in the argument block.
    DexType[] parameters = proto.parameters.values;
    for (int i = 0; i < parameters.length; i++) {
      com.debughelper.tools.r8.ir.code.ValueType valueType = ValueType.fromDexType(parameters[i]);
      Value paramValue = builder.writeRegister(paramRegisters[i], valueType, BasicBlock.ThrowingInfo.NO_THROW);
      paramValues[i] = paramValue;
      builder.add(new Argument(paramValue));
    }
  }

  @Override
  public final void buildPostlude(com.debughelper.tools.r8.ir.conversion.IRBuilder builder) {
    // Intentionally left empty.
  }

  @Override
  public final void buildInstruction(
          com.debughelper.tools.r8.ir.conversion.IRBuilder builder, int instructionIndex, boolean firstBlockInstruction) {
    constructors.get(instructionIndex).accept(builder);
  }

  @Override
  public final void resolveAndBuildSwitch(
      int value, int fallthroughOffset, int payloadOffset, com.debughelper.tools.r8.ir.conversion.IRBuilder builder) {
    throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected call to resolveAndBuildSwitch");
  }

  @Override
  public final void resolveAndBuildNewArrayFilledData(
      int arrayRef, int payloadOffset, com.debughelper.tools.r8.ir.conversion.IRBuilder builder) {
    throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected call to resolveAndBuildNewArrayFilledData");
  }

  @Override
  public final CatchHandlers<Integer> getCurrentCatchHandlers() {
    return null;
  }

  @Override
  public int getMoveExceptionRegister() {
    throw new com.debughelper.tools.r8.errors.Unreachable();
  }

  @Override
  public com.debughelper.tools.r8.ir.code.Position getDebugPositionAtOffset(int offset) {
    throw new Unreachable();
  }

  @Override
  public com.debughelper.tools.r8.ir.code.Position getCurrentPosition() {
    return Position.none();
  }

  @Override
  public final boolean verifyCurrentInstructionCanThrow() {
    return true;
  }

  @Override
  public boolean verifyLocalInScope(DebugLocalInfo local) {
    return true;
  }

  @Override
  public final boolean verifyRegister(int register) {
    return true;
  }

  // To be used as a tracing event for switch instruction.,
  protected boolean endsSwitch(
          IRBuilder builder, int switchIndex, int fallthrough, int[] offsets) {
    // ensure successors of switch instruction
    for (int offset : offsets) {
      builder.ensureNormalSuccessorBlock(switchIndex, offset);
    }
    builder.ensureNormalSuccessorBlock(switchIndex, fallthrough);
    return true;
  }
}
