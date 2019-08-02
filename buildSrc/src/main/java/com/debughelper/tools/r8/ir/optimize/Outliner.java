// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.ir.code.BasicBlock;
import com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo;
import com.debughelper.tools.r8.ir.code.Invoke;
import com.debughelper.tools.r8.ir.code.Invoke.Type;
import com.debughelper.tools.r8.ir.optimize.Inliner.Constraint;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.InternalOptions.OutlineOptions;
import com.debughelper.tools.r8.utils.StringUtils;
import com.debughelper.tools.r8.utils.StringUtils.BraceType;
import com.debughelper.tools.r8.dex.Constants;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.ClassAccessFlags;
import com.debughelper.tools.r8.graph.Code;
import com.debughelper.tools.r8.graph.DebugLocalInfo;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexTypeList;
import com.debughelper.tools.r8.graph.MethodAccessFlags;
import com.debughelper.tools.r8.graph.ParameterAnnotationsList;
import com.debughelper.tools.r8.graph.UseRegistry;
import com.debughelper.tools.r8.ir.code.Add;
import com.debughelper.tools.r8.ir.code.CatchHandlers;
import com.debughelper.tools.r8.ir.code.Div;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Instruction;
import com.debughelper.tools.r8.ir.code.InstructionListIterator;
import com.debughelper.tools.r8.ir.code.InvokeMethod;
import com.debughelper.tools.r8.ir.code.InvokeStatic;
import com.debughelper.tools.r8.ir.code.Mul;
import com.debughelper.tools.r8.ir.code.NewInstance;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.ir.code.Rem;
import com.debughelper.tools.r8.ir.code.Sub;
import com.debughelper.tools.r8.ir.code.Value;
import com.debughelper.tools.r8.ir.code.ValueType;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.ir.conversion.SourceCode;
import com.debughelper.tools.r8.naming.ClassNameMapper;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.origin.SynthesizedOrigin;
import com.debughelper.tools.r8.shaking.Enqueuer;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.BiConsumer;
import org.objectweb.asm.Opcodes;

/**
 * Support class for implementing outlining (i.e. extracting common code patterns as methods).
 *
 * <p>Outlining happens in three steps.
 *
 * <ul>
 *   <li>First, all methods are converted to IR and passed to {@link
 *       Outliner#identifyCandidateMethods()} to identify outlining candidates and the methods
 *       containing each candidate. IR is converted to the output format (DEX or CF) and thrown away
 *       along with the outlining candidates; only a list of lists of methods is kept, where each
 *       list of methods corresponds to methods containing an outlining candidate.
 *   <li>Second, {@link Outliner#selectMethodsForOutlining()} is called to retain the lists of
 *       methods found in the first step that are large enough (see {@link com.debughelper.tools.r8.utils.InternalOptions#outline}
 *       {@link com.debughelper.tools.r8.utils.InternalOptions.OutlineOptions#threshold}), and the methods to be further analyzed for outlining is
 *       returned by {@link Outliner#getMethodsSelectedForOutlining}. Each selected method is then
 *       converted back to IR and passed to {@link Outliner#identifyOutlineSites(com.debughelper.tools.r8.ir.code.IRCode,
 *       com.debughelper.tools.r8.graph.DexEncodedMethod)}, which then stores concrete outlining candidates in {@link
 *       Outliner#outlineSites}.
 *   <li>Third, {@link Outliner#buildOutlinerClass(com.debughelper.tools.r8.graph.DexType)} is called to construct the <em>outline
 *       support class</em> containing a static helper method for each outline candidate that occurs
 *       frequently enough. Each selected method is then converted to IR, passed to {@link
 *       Outliner#applyOutliningCandidate(com.debughelper.tools.r8.ir.code.IRCode, com.debughelper.tools.r8.graph.DexEncodedMethod)} to perform the outlining, and
 *       converted back to the output format (DEX or CF).
 * </ul>
 */
public class Outliner {

  private final com.debughelper.tools.r8.utils.InternalOptions options;
  /** Result of first step (see {@link Outliner#identifyCandidateMethods()}. */
  private final List<List<com.debughelper.tools.r8.graph.DexEncodedMethod>> candidateMethodLists = new ArrayList<>();
  /** Result of second step (see {@link Outliner#selectMethodsForOutlining()}. */
  private final Set<com.debughelper.tools.r8.graph.DexEncodedMethod> methodsSelectedForOutlining = Sets.newIdentityHashSet();
  /** Result of second step (see {@link Outliner#selectMethodsForOutlining()}. */
  private final Map<Outline, List<com.debughelper.tools.r8.graph.DexEncodedMethod>> outlineSites = new HashMap<>();
  /** Result of third step (see {@link Outliner#buildOutlinerClass(com.debughelper.tools.r8.graph.DexType)}. */
  private final Map<Outline, com.debughelper.tools.r8.graph.DexMethod> generatedOutlines = new HashMap<>();

  static final int MAX_IN_SIZE = 5;  // Avoid using ranged calls for outlined code.

  final private Enqueuer.AppInfoWithLiveness appInfo;
  final private DexItemFactory dexItemFactory;

  // Representation of an outline.
  // This includes the instructions in the outline, and a map from the arguments of this outline
  // to the values in the instructions.
  //
  // E.g. an outline of two StringBuilder.append(String) calls could look like this:
  //
  //  InvokeVirtual       { v5 v6 } Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
  //  InvokeVirtual       { v5 v9 } Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
  //  ReturnVoid
  //
  // It takes three arguments
  //
  //   v5, v6, v9
  //
  // and the argument map is a list mapping the arguments to the in-values of all instructions in
  // the order they are encountered, in this case:
  //
  //   [0, 1, 0, 2]
  //
  // The actual value numbers (in this example v5, v6, v9 are "arbitrary", as the instruction in
  // the outline are taken from the block where they are collected as candidates. The comparison
  // of two outlines rely on the instructions and the argument mapping *not* the concrete values.
  public class Outline implements Comparable<Outline> {

    final List<com.debughelper.tools.r8.ir.code.Value> arguments;
    final List<com.debughelper.tools.r8.graph.DexType> argumentTypes;
    final List<Integer> argumentMap;
    final List<com.debughelper.tools.r8.ir.code.Instruction> templateInstructions = new ArrayList<>();
    final public com.debughelper.tools.r8.graph.DexType returnType;

    private com.debughelper.tools.r8.graph.DexProto proto;

    // Build an outline over the instructions [start, end[.
    // The arguments are the arguments to pass to an outline of these instructions.
    Outline(List<com.debughelper.tools.r8.ir.code.Instruction> instructions, List<com.debughelper.tools.r8.ir.code.Value> arguments, List<com.debughelper.tools.r8.graph.DexType> argumentTypes,
            List<Integer> argumentMap, com.debughelper.tools.r8.graph.DexType returnType, int start, int end) {
      this.arguments = arguments;
      this.argumentTypes = argumentTypes;
      this.argumentMap = argumentMap;
      this.returnType = returnType;

      for (int i = start; i < end; i++) {
        com.debughelper.tools.r8.ir.code.Instruction current = instructions.get(i);
        if (current.isInvoke() || current.isNewInstance() || current.isArithmeticBinop()) {
          templateInstructions.add(current);
        } else if (current.isConstInstruction()) {
          // Don't include const instructions in the template.
        } else {
          assert false : "Unexpected type of instruction in outlining template.";
        }
      }
    }

    int argumentCount() {
      return arguments.size();
    }

    com.debughelper.tools.r8.graph.DexProto buildProto() {
      if (proto == null) {
        com.debughelper.tools.r8.graph.DexType[] argumentTypesArray = argumentTypes.toArray(new com.debughelper.tools.r8.graph.DexType[argumentTypes.size()]);
        proto = dexItemFactory.createProto(returnType, argumentTypesArray);
      }
      return proto;
    }

    // Build the DexMethod for this outline.
    com.debughelper.tools.r8.graph.DexMethod buildMethod(com.debughelper.tools.r8.graph.DexType clazz, com.debughelper.tools.r8.graph.DexString name) {
      return dexItemFactory.createMethod(clazz, buildProto(), name);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Outline)) {
        return false;
      }
      List<com.debughelper.tools.r8.ir.code.Instruction> instructions0 = this.templateInstructions;
      List<com.debughelper.tools.r8.ir.code.Instruction> instructions1 = ((Outline) other).templateInstructions;
      if (instructions0.size() != instructions1.size()) {
        return false;
      }
      for (int i = 0; i < instructions0.size(); i++) {
        com.debughelper.tools.r8.ir.code.Instruction i0 = instructions0.get(i);
        com.debughelper.tools.r8.ir.code.Instruction i1 = instructions1.get(i);
        // Note that we don't consider positions as this optimization already breaks stack traces.
        if (!i0.identicalNonValueNonPositionParts(i1)) {
          return false;
        }
        if ((i0.outValue() != null) != (i1.outValue() != null)) {
          return false;
        }
      }
      return argumentMap.equals(((Outline) other).argumentMap)
          && returnType == ((Outline) other).returnType;
    }

    @Override
    public int hashCode() {
      final int MAX_HASH_INSTRUCTIONS = 5;

      int hash = templateInstructions.size();
      int hashPart = 0;
      for (int i = 0; i < templateInstructions.size() && i < MAX_HASH_INSTRUCTIONS; i++) {
        com.debughelper.tools.r8.ir.code.Instruction instruction = templateInstructions.get(i);
        if (instruction.isInvokeMethod()) {
          hashPart = hashPart << 4;
          hashPart += instruction.asInvokeMethod().getInvokedMethod().hashCode();
        }
        hash = hash * 3 + hashPart;
      }
      return hash;
    }

    @Override
    public int compareTo(Outline other) {
      if (this == other) {
        return 0;
      }
      // First compare the proto.
      int result;
      result = buildProto().slowCompareTo(other.buildProto());
      if (result != 0) {
        assert !equals(other);
        return result;
      }
      assert argumentCount() == other.argumentCount();
      // Then compare the instructions (non value part).
      List<com.debughelper.tools.r8.ir.code.Instruction> instructions0 = templateInstructions;
      List<com.debughelper.tools.r8.ir.code.Instruction> instructions1 = other.templateInstructions;
      result = instructions0.size() - instructions1.size();
      if (result != 0) {
        assert !equals(other);
        return result;
      }
      for (int i = 0; i < instructions0.size(); i++) {
        com.debughelper.tools.r8.ir.code.Instruction i0 = instructions0.get(i);
        com.debughelper.tools.r8.ir.code.Instruction i1 = instructions1.get(i);
        result = i0.getInstructionName().compareTo(i1.getInstructionName());
        if (result != 0) {
          assert !equals(other);
          return result;
        }
        result = i0.inValues().size() - i1.inValues().size();
        if (result != 0) {
          assert !equals(other);
          return result;
        }
        result = (i0.outValue() != null ? 1 : 0) - (i1.outValue() != null ? 1 : 0);
        if (result != 0) {
          assert !equals(other);
          return result;
        }
        result = i0.compareNonValueParts(i1);
        if (result != 0) {
          assert !equals(other);
          return result;
        }
      }
      // Finally compare the value part.
      result = argumentMap.size() - other.argumentMap.size();
      if (result != 0) {
        assert !equals(other);
        return result;
      }
      for (int i = 0; i < argumentMap.size(); i++) {
        result = argumentMap.get(i) - other.argumentMap.get(i);
        if (result != 0) {
          assert !equals(other);
          return result;
        }
      }
      assert equals(other);
      return 0;
    }

    @Override
    public String toString() {
      // The printing of the code for an outline maps the value numbers to the arguments numbers.
      int outRegisterNumber = arguments.size();
      StringBuilder builder = new StringBuilder();
      builder.append(returnType);
      builder.append(" anOutline");
      com.debughelper.tools.r8.utils.StringUtils.append(builder, argumentTypes, ", ", com.debughelper.tools.r8.utils.StringUtils.BraceType.PARENS);
      builder.append("\n");
      int argumentMapIndex = 0;
      for (com.debughelper.tools.r8.ir.code.Instruction instruction : templateInstructions) {
        String name = instruction.getInstructionName();
        com.debughelper.tools.r8.utils.StringUtils.appendRightPadded(builder, name, 20);
        if (instruction.outValue() != null) {
          builder.append("v" + outRegisterNumber);
          builder.append(" <- ");
        }
        for (int i = 0; i < instruction.inValues().size(); i++) {
          builder.append(i > 0 ? ", " : "");
          builder.append("v");
          int index = argumentMap.get(argumentMapIndex++);
          if (index >= 0) {
            builder.append(index);
          } else {
            builder.append(outRegisterNumber);
          }
        }
        if (instruction.isInvoke()) {
          builder.append("; method: ");
          builder.append(instruction.asInvokeMethod().getInvokedMethod().toSourceString());
        }
        if (instruction.isNewInstance()) {
          builder.append(instruction.asNewInstance().clazz.toSourceString());
        }
        builder.append("\n");
      }
      if (returnType == dexItemFactory.voidType) {
        builder.append("Return-Void");
      } else {
        com.debughelper.tools.r8.utils.StringUtils.appendRightPadded(builder, "Return", 20);
        builder.append("v" + (outRegisterNumber));
      }
      builder.append("\n");
      builder.append(argumentMap);
      return builder.toString();
    }
  }

  // Spot the outline opportunities in a basic block.
  // This is the superclass for both collection candidates and actually replacing code.
  // TODO(sgjesse): Collect more information in the candidate collection and reuse that for
  // replacing.
  abstract private class OutlineSpotter {

    final com.debughelper.tools.r8.graph.DexEncodedMethod method;
    final com.debughelper.tools.r8.ir.code.BasicBlock block;
    // instructionArrayCache is block.getInstructions() copied to an ArrayList.
    private List<com.debughelper.tools.r8.ir.code.Instruction> instructionArrayCache = null;

    int start;
    int index;
    int actualInstructions;
    List<com.debughelper.tools.r8.ir.code.Value> arguments;
    List<com.debughelper.tools.r8.graph.DexType> argumentTypes;
    List<Integer> argumentsMap;
    int argumentRegisters;
    com.debughelper.tools.r8.graph.DexType returnType;
    com.debughelper.tools.r8.ir.code.Value returnValue;
    int returnValueUsersLeft;
    int pendingNewInstanceIndex = -1;

    OutlineSpotter(com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.BasicBlock block) {
      this.method = method;
      this.block = block;
      reset(0);
    }

    protected List<com.debughelper.tools.r8.ir.code.Instruction> getInstructionArray() {
      if (instructionArrayCache == null) {
        instructionArrayCache = new ArrayList<>(block.getInstructions());
      }
      return instructionArrayCache;
    }

    // Call this before modifying block.getInstructions().
    protected void invalidateInstructionArray() {
      instructionArrayCache = null;
    }

    protected void process() {
      List<com.debughelper.tools.r8.ir.code.Instruction> instructions;
      for (;;) {
        instructions = getInstructionArray(); // ProcessInstruction may have invalidated it.
        if (index >= instructions.size()) {
          break;
        }
        processInstruction(instructions.get(index));
      }
    }

    // Get int in-values for an instruction. For commutative binary operations using the current
    // return value (active out-value) make sure that that value is the left value.
    protected List<com.debughelper.tools.r8.ir.code.Value> orderedInValues(com.debughelper.tools.r8.ir.code.Instruction instruction, com.debughelper.tools.r8.ir.code.Value returnValue) {
      List<com.debughelper.tools.r8.ir.code.Value> inValues = instruction.inValues();
      if (instruction.isBinop() && instruction.asBinop().isCommutative()) {
        if (inValues.get(1) == returnValue) {
          com.debughelper.tools.r8.ir.code.Value tmp = inValues.get(0);
          inValues.set(0, inValues.get(1));
          inValues.set(1, tmp);
        }
      }
      return inValues;
    }

    // Process instruction. Returns true if an outline candidate was found.
    private void processInstruction(com.debughelper.tools.r8.ir.code.Instruction instruction) {
      // Figure out whether to include the instruction.
      boolean include = false;
      int instructionIncrement = 1;
      if (instruction.isConstInstruction()) {
        if (index == start) {
          // Restart for first const instruction.
          reset(index + 1);
          return;
        } else {
          // Otherwise include const instructions.
          include = true;
          instructionIncrement = 0;
        }
      } else {
        include = canIncludeInstruction(instruction);
      }

      if (include) {
        actualInstructions += instructionIncrement;

        // Add this instruction.
        includeInstruction(instruction);
        // Check if this instruction ends the outline.
        if (actualInstructions >= options.outline.maxSize) {
          candidate(start, index + 1);
        } else {
          index++;
        }
      } else if (index > start) {
        // Do not add this instruction, candidate ends with previous instruction.
        candidate(start, index);
      } else {
        // Restart search from next instruction.
        reset(index + 1);
      }
    }

    // Check if the current instruction can be included in the outline.
    private boolean canIncludeInstruction(com.debughelper.tools.r8.ir.code.Instruction instruction) {
      // Find the users of the active out-value (potential return value).
      int returnValueUsersLeftIfIncluded = returnValueUsersLeft;
      if (returnValue != null) {
        for (com.debughelper.tools.r8.ir.code.Value value : instruction.inValues()) {
          if (value == returnValue) {
            returnValueUsersLeftIfIncluded--;
          }
        }
      }

      // If this instruction has an out-value, but the previous one is still active end the
      // outline.
      if (instruction.outValue() != null && returnValueUsersLeftIfIncluded > 0) {
        return false;
      }

      // Allow all new-instance instructions in a outline.
      if (instruction.isNewInstance()) {
        if (instruction.outValue().isUsed()) {
          // Track the new-instance value to make sure the <init> call is part of the outline.
          pendingNewInstanceIndex = index;
        }
        return true;
      }

      if (instruction.isArithmeticBinop()) {
        return true;
      }

      // Otherwise we only allow invoke.
      if (!instruction.isInvokeMethod()) {
        return false;
      }
      com.debughelper.tools.r8.ir.code.InvokeMethod invoke = instruction.asInvokeMethod();
      boolean constructor = dexItemFactory.isConstructor(invoke.getInvokedMethod());

      // See whether we could move this invoke somewhere else. We reuse the logic from inlining
      // here, as the constraints are the same.
      Constraint constraint = invoke.inliningConstraint(appInfo, method.method.holder);
      if (constraint != Constraint.ALWAYS) {
        return false;
      }
      // Find the number of in-going arguments, if adding this instruction.
      int newArgumentRegisters = argumentRegisters;
      if (instruction.inValues().size() > 0) {
        List<com.debughelper.tools.r8.ir.code.Value> inValues = orderedInValues(instruction, returnValue);
        for (int i = 0; i < inValues.size(); i++) {
          com.debughelper.tools.r8.ir.code.Value value = inValues.get(i);
          if (value == returnValue) {
            continue;
          }
          if (invoke.isInvokeStatic()) {
            newArgumentRegisters += value.requiredRegisters();
          } else {
            // For virtual calls only re-use the receiver argument.
            if (i > 0 || !arguments.contains(value)) {
              newArgumentRegisters += value.requiredRegisters();
            }
          }
        }
      }
      if (newArgumentRegisters > MAX_IN_SIZE) {
        return false;
      }

      // Only include constructors if the previous instruction is the corresponding new-instance.
      if (constructor) {
        if (start == index) {
          return false;
        }
        assert index > 0;
        int offset = 0;
        com.debughelper.tools.r8.ir.code.Instruction previous;
        List<com.debughelper.tools.r8.ir.code.Instruction> instructions = getInstructionArray();
        do {
          offset++;
          previous = instructions.get(index - offset);
        } while (previous.isConstInstruction());
        if (!previous.isNewInstance() || previous.outValue() != returnValue) {
          return false;
        }
        // Clear pending new instance flag as the last thing, now the matching constructor is known
        // to be included.
        pendingNewInstanceIndex = -1;
      }
      return true;
    }

    // Add the current instruction to the outline.
    private void includeInstruction(com.debughelper.tools.r8.ir.code.Instruction instruction) {
      List<com.debughelper.tools.r8.ir.code.Value> inValues = orderedInValues(instruction, returnValue);

      com.debughelper.tools.r8.ir.code.Value prevReturnValue = returnValue;
      if (returnValue != null) {
        for (com.debughelper.tools.r8.ir.code.Value value : inValues) {
          if (value == returnValue) {
            assert returnValueUsersLeft > 0;
            returnValueUsersLeft--;
          }
          if (returnValueUsersLeft == 0) {
            returnValue = null;
            returnType = dexItemFactory.voidType;
          }
        }
      }

      if (instruction.isNewInstance()) {
        assert returnValue == null;
        updateReturnValueState(instruction.outValue(), instruction.asNewInstance().clazz);
        return;
      }

      assert instruction.isInvoke()
          || instruction.isConstInstruction()
          || instruction.isArithmeticBinop();
      if (inValues.size() > 0) {
        for (int i = 0; i < inValues.size(); i++) {
          com.debughelper.tools.r8.ir.code.Value value = inValues.get(i);
          if (value == prevReturnValue) {
            argumentsMap.add(-1);
            continue;
          }
          if (instruction.isInvoke()
              && instruction.asInvoke().getType() != com.debughelper.tools.r8.ir.code.Invoke.Type.STATIC
              && instruction.asInvoke().getType() != com.debughelper.tools.r8.ir.code.Invoke.Type.CUSTOM) {
            InvokeMethod invoke = instruction.asInvokeMethod();
            int argumentIndex = arguments.indexOf(value);
            // For virtual calls only re-use the receiver argument.
            if (i == 0 && argumentIndex != -1) {
              argumentsMap.add(argumentIndex);
            } else {
              arguments.add(value);
              argumentRegisters += value.requiredRegisters();
              if (i == 0) {
                argumentTypes.add(invoke.getInvokedMethod().getHolder());
              } else {
                DexProto methodProto;
                if (instruction.asInvoke().getType() == com.debughelper.tools.r8.ir.code.Invoke.Type.POLYMORPHIC) {
                  // Type of argument of a polymorphic call must be take from the call site.
                  methodProto = instruction.asInvokePolymorphic().getProto();
                } else {
                  methodProto = invoke.getInvokedMethod().proto;
                }
                // -1 due to receiver.
                argumentTypes.add(methodProto.parameters.values[i - 1]);
              }
              argumentsMap.add(arguments.size() - 1);
            }
          } else {
            arguments.add(value);
            if (instruction.isInvokeMethod()) {
              argumentTypes
                  .add(instruction.asInvokeMethod().getInvokedMethod().proto.parameters.values[i]);
            } else {
              argumentTypes.add(instruction.asBinop().getNumericType().dexTypeFor(dexItemFactory));
            }
            argumentsMap.add(arguments.size() - 1);
          }
        }
      }
      if (!instruction.isConstInstruction() && instruction.outValue() != null) {
        assert returnValue == null;
        if (instruction.isInvokeMethod()) {
          updateReturnValueState(
              instruction.outValue(),
              instruction.asInvokeMethod().getInvokedMethod().proto.returnType);
        } else {
          updateReturnValueState(
              instruction.outValue(),
              instruction.asBinop().getNumericType().dexTypeFor(dexItemFactory));
        }
      }
    }

    private void updateReturnValueState(com.debughelper.tools.r8.ir.code.Value newReturnValue, com.debughelper.tools.r8.graph.DexType newReturnType) {
      returnValueUsersLeft = newReturnValue.numberOfAllUsers();
      // If the return value is not used don't track it.
      if (returnValueUsersLeft == 0) {
        returnValue = null;
        returnType = dexItemFactory.voidType;
      } else {
        returnValue = newReturnValue;
        returnType = newReturnType;
      }
    }


    protected abstract void handle(int start, int end, Outline outline);

    private void candidate(int start, int index) {
      List<com.debughelper.tools.r8.ir.code.Instruction> instructions = getInstructionArray();
      assert !instructions.get(start).isConstInstruction();

      if (pendingNewInstanceIndex != -1) {
        if (pendingNewInstanceIndex == start) {
          reset(index);
        } else {
          reset(pendingNewInstanceIndex);
        }
        return;
      }

      // Back out of any const instructions ending this candidate.
      int end = index;
      while (instructions.get(end - 1).isConstInstruction()) {
        end--;
      }

      // Check if the candidate qualifies.
      int nonConstInstructions = 0;
      for (int i = start; i < end; i++) {
        if (!instructions.get(i).isConstInstruction()) {
          nonConstInstructions++;
        }
      }
      if (nonConstInstructions < options.outline.minSize) {
        reset(start + 1);
        return;
      }

      Outline outline = new Outline(
          instructions, arguments, argumentTypes, argumentsMap, returnType, start, end);
      handle(start, end, outline);

      // Start a new candidate search from the next instruction after this outline.
      reset(index);
    }

    // Restart the collection of outline candidate to the given instruction start index.
    private void reset(int startIndex) {
      start = startIndex;
      index = startIndex;
      actualInstructions = 0;
      arguments = new ArrayList<>(MAX_IN_SIZE);
      argumentTypes = new ArrayList<>(MAX_IN_SIZE);
      argumentsMap = new ArrayList<>(MAX_IN_SIZE);
      argumentRegisters = 0;
      returnType = dexItemFactory.voidType;
      returnValue = null;
      returnValueUsersLeft = 0;
      pendingNewInstanceIndex = -1;
    }
  }

  // Collect outlining candidates with the methods that can use them.
  // TODO(sgjesse): This does not take several usages in the same method into account.
  private class OutlineMethodIdentifier extends OutlineSpotter {

    private final Map<Outline, List<com.debughelper.tools.r8.graph.DexEncodedMethod>> candidateMap;

    OutlineMethodIdentifier(
        com.debughelper.tools.r8.graph.DexEncodedMethod method,
        com.debughelper.tools.r8.ir.code.BasicBlock block,
        Map<Outline, List<com.debughelper.tools.r8.graph.DexEncodedMethod>> candidateMap) {
      super(method, block);
      this.candidateMap = candidateMap;
    }

    @Override
    protected void handle(int start, int end, Outline outline) {
      synchronized (candidateMap) {
        candidateMap.computeIfAbsent(outline, this::addOutlineMethodList).add(method);
      }
    }

    private List<com.debughelper.tools.r8.graph.DexEncodedMethod> addOutlineMethodList(Outline outline) {
      List<com.debughelper.tools.r8.graph.DexEncodedMethod> result = new ArrayList<>();
      candidateMethodLists.add(result);
      return result;
    }
  }

  private class OutlineSiteIdentifier extends OutlineSpotter {

    OutlineSiteIdentifier(com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.BasicBlock block) {
      super(method, block);
    }

    @Override
    protected void handle(int start, int end, Outline outline) {
      synchronized (outlineSites) {
        outlineSites.computeIfAbsent(outline, k -> new ArrayList<>()).add(method);
      }
    }
  }

  // Replace instructions with a call to the outlined method.
  private class OutlineRewriter extends OutlineSpotter {

    private final com.debughelper.tools.r8.ir.code.IRCode code;
    private final ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blocksIterator;
    private final List<Integer> toRemove;
    int argumentsMapIndex;

    OutlineRewriter(
            com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.ir.code.IRCode code,
            ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blocksIterator, com.debughelper.tools.r8.ir.code.BasicBlock block, List<Integer> toRemove) {
      super(method, block);
      this.code = code;
      this.blocksIterator = blocksIterator;
      this.toRemove = toRemove;
    }

    @Override
    protected void handle(int start, int end, Outline outline) {
      com.debughelper.tools.r8.graph.DexMethod m = generatedOutlines.get(outline);
      if (m != null) {
        assert removeMethodFromOutlineList(outline);
        List<com.debughelper.tools.r8.ir.code.Value> in = new ArrayList<>();
        com.debughelper.tools.r8.ir.code.Value returnValue = null;
        argumentsMapIndex = 0;
        com.debughelper.tools.r8.ir.code.Position position = com.debughelper.tools.r8.ir.code.Position.none();
        { // Scope for 'instructions'.
          List<com.debughelper.tools.r8.ir.code.Instruction> instructions = getInstructionArray();
          for (int i = start; i < end; i++) {
            com.debughelper.tools.r8.ir.code.Instruction current = instructions.get(i);
            if (current.isConstInstruction()) {
              // Leave any const instructions.
              continue;
            }
            if (position.isNone()) {
              position = current.getPosition();
            }
            // Prepare to remove the instruction.
            List<com.debughelper.tools.r8.ir.code.Value> inValues = orderedInValues(current, returnValue);
            for (int j = 0; j < inValues.size(); j++) {
              com.debughelper.tools.r8.ir.code.Value value = inValues.get(j);
              value.removeUser(current);
              int argumentIndex = outline.argumentMap.get(argumentsMapIndex++);
              if (argumentIndex >= in.size()) {
                assert argumentIndex == in.size();
                in.add(value);
              }
            }
            if (current.outValue() != null) {
              returnValue = current.outValue();
            }
            // The invoke of the outline method will be placed at the last instruction index,
            // so don't mark that for removal.
            if (i < end - 1) {
              toRemove.add(i);
            }
          }
        }
        assert m.proto.shorty.toString().length() - 1 == in.size();
        if (returnValue != null && !returnValue.isUsed()) {
          returnValue = null;
        }
        com.debughelper.tools.r8.ir.code.Invoke outlineInvoke = new InvokeStatic(m, returnValue, in);
        outlineInvoke.setBlock(block);
        outlineInvoke.setPosition(position);
        if (position.isNone() && code.doAllThrowingInstructionsHavePositions()) {
          // We have introduced a static invoke, but non of the outlines instructions could throw
          // and none had a position. The code no longer has the previous property.
          code.setAllThrowingInstructionsHavePositions(false);
        }
        InstructionListIterator endIterator = block.listIterator(end - 1);
        com.debughelper.tools.r8.ir.code.Instruction instructionBeforeEnd = endIterator.next();
        invalidateInstructionArray(); // Because we're about to modify the original linked list.
        instructionBeforeEnd.clearBlock();
        endIterator.set(outlineInvoke); // Replaces instructionBeforeEnd.
        if (block.hasCatchHandlers()) {
          // If the inserted invoke is inserted in a block with handlers, split the block after
          // the inserted invoke.
          endIterator.split(code, blocksIterator);
        }
      }
    }

    /** When assertions are enabled, remove method from the outline's list. */
    private boolean removeMethodFromOutlineList(Outline outline) {
      synchronized (outlineSites) {
        assert outlineSites.get(outline).remove(method);
      }
      return true;
    }
  }

  public Outliner(Enqueuer.AppInfoWithLiveness appInfo, com.debughelper.tools.r8.utils.InternalOptions options) {
    this.appInfo = appInfo;
    this.dexItemFactory = appInfo.dexItemFactory;
    this.options = options;
  }

  public BiConsumer<com.debughelper.tools.r8.ir.code.IRCode, com.debughelper.tools.r8.graph.DexEncodedMethod> identifyCandidateMethods() {
    // Since optimizations may change the map identity of Outline objects (e.g. by setting the
    // out-value of invokes to null), this map must not be used except for identifying methods
    // potentially relevant to outlining. OutlineMethodIdentifier will add method lists to
    // candidateMethodLists whenever it adds an entry to candidateMap.
    Map<Outline, List<com.debughelper.tools.r8.graph.DexEncodedMethod>> candidateMap = new HashMap<>();
    assert candidateMethodLists.isEmpty();
    return (code, method) -> {
      assert !(method.getCode() instanceof OutlineCode);
      for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
        new OutlineMethodIdentifier(method, block, candidateMap).process();
      }
    };
  }

  public void identifyOutlineSites(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    assert !(method.getCode() instanceof OutlineCode);
    for (com.debughelper.tools.r8.ir.code.BasicBlock block : code.blocks) {
      new OutlineSiteIdentifier(method, block).process();
    }
  }

  public boolean selectMethodsForOutlining() {
    assert methodsSelectedForOutlining.size() == 0;
    assert outlineSites.size() == 0;
    for (List<com.debughelper.tools.r8.graph.DexEncodedMethod> outlineMethods : candidateMethodLists) {
      if (outlineMethods.size() >= options.outline.threshold) {
        methodsSelectedForOutlining.addAll(outlineMethods);
      }
    }
    candidateMethodLists.clear();
    return methodsSelectedForOutlining.size() > 0;
  }

  public Set<com.debughelper.tools.r8.graph.DexEncodedMethod> getMethodsSelectedForOutlining() {
    return methodsSelectedForOutlining;
  }

  public com.debughelper.tools.r8.graph.DexProgramClass buildOutlinerClass(com.debughelper.tools.r8.graph.DexType type) {
    // Build the outlined methods.
    // By now the candidates are the actual selected outlines. Name the generated methods in a
    // consistent order, to provide deterministic output.
    List<Outline> outlines = selectOutlines();
    outlines.sort(Comparator.naturalOrder());
    com.debughelper.tools.r8.graph.DexEncodedMethod[] direct = new com.debughelper.tools.r8.graph.DexEncodedMethod[outlines.size()];
    int count = 0;
    for (Outline outline : outlines) {
      com.debughelper.tools.r8.graph.MethodAccessFlags methodAccess =
          MethodAccessFlags.fromSharedAccessFlags(
              com.debughelper.tools.r8.dex.Constants.ACC_PUBLIC | com.debughelper.tools.r8.dex.Constants.ACC_STATIC, false);
      com.debughelper.tools.r8.graph.DexString methodName = dexItemFactory.createString(com.debughelper.tools.r8.utils.InternalOptions.OutlineOptions.METHOD_PREFIX + count);
      DexMethod method = outline.buildMethod(type, methodName);
      List<com.debughelper.tools.r8.graph.DexEncodedMethod> sites = outlineSites.get(outline);
      assert !sites.isEmpty();
      direct[count] =
          new com.debughelper.tools.r8.graph.DexEncodedMethod(
              method,
              methodAccess,
              com.debughelper.tools.r8.graph.DexAnnotationSet.empty(),
              ParameterAnnotationsList.empty(),
              new OutlineCode(outline));
      if (options.isGeneratingClassFiles()) {
        direct[count].upgradeClassFileVersion(sites.get(0).getClassFileVersion());
      }
      generatedOutlines.put(outline, method);
      count++;
    }
    // No need to sort the direct methods as they are generated in sorted order.

    // Build the outliner class.
    DexType superType = dexItemFactory.createType("Ljava/lang/Object;");
    com.debughelper.tools.r8.graph.DexTypeList interfaces = DexTypeList.empty();
    DexString sourceFile = dexItemFactory.createString("outline");
    com.debughelper.tools.r8.graph.ClassAccessFlags accessFlags = ClassAccessFlags.fromSharedAccessFlags(Constants.ACC_PUBLIC);
    com.debughelper.tools.r8.graph.DexProgramClass clazz =
        new DexProgramClass(
            type,
            null,
            new SynthesizedOrigin("outlining", getClass()),
            accessFlags,
            superType,
            interfaces,
            sourceFile,
            null,
            Collections.emptyList(),
            // TODO: Build dex annotations structure.
            DexAnnotationSet.empty(),
            com.debughelper.tools.r8.graph.DexEncodedField.EMPTY_ARRAY, // Static fields.
            DexEncodedField.EMPTY_ARRAY, // Instance fields.
            direct,
            com.debughelper.tools.r8.graph.DexEncodedMethod.EMPTY_ARRAY, // Virtual methods.
            options.itemFactory.getSkipNameValidationForTesting());
    if (options.isGeneratingClassFiles()) {
      // All program classes must have a class-file version. Use Java 6.
      clazz.setClassFileVersion(Opcodes.V1_6);
    }
    return clazz;
  }

  private List<Outline> selectOutlines() {
    assert outlineSites.size() > 0;
    assert candidateMethodLists.isEmpty();
    List<Outline> result = new ArrayList<>();
    for (Entry<Outline, List<com.debughelper.tools.r8.graph.DexEncodedMethod>> entry : outlineSites.entrySet()) {
      if (entry.getValue().size() >= options.outline.threshold) {
        result.add(entry.getKey());
      }
    }
    return result;
  }

  public void applyOutliningCandidate(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    assert !(method.getCode() instanceof OutlineCode);
    ListIterator<com.debughelper.tools.r8.ir.code.BasicBlock> blocksIterator = code.blocks.listIterator();
    while (blocksIterator.hasNext()) {
      com.debughelper.tools.r8.ir.code.BasicBlock block = blocksIterator.next();
      List<Integer> toRemove = new ArrayList<>();
      new OutlineRewriter(method, code, blocksIterator, block, toRemove).process();
      block.removeInstructions(toRemove);
    }
  }

  public boolean checkAllOutlineSitesFoundAgain() {
    for (Outline outline : generatedOutlines.keySet()) {
      assert outlineSites.get(outline).isEmpty() : outlineSites.get(outline);
    }
    return true;
  }

  static public void noProcessing(com.debughelper.tools.r8.ir.code.IRCode code, com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    // No operation.
  }

  private class OutlineSourceCode implements SourceCode {

    final private Outline outline;
    int argumentMapIndex = 0;

    OutlineSourceCode(Outline outline) {
      this.outline = outline;
    }

    @Override
    public int instructionCount() {
      return outline.templateInstructions.size() + 1;
    }

    @Override
    public int instructionIndex(int instructionOffset) {
      return instructionOffset;
    }

    @Override
    public int instructionOffset(int instructionIndex) {
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
    public int traceInstruction(int instructionIndex, com.debughelper.tools.r8.ir.conversion.IRBuilder builder) {
      // There is just one block, and after the last instruction it is closed.
      return instructionIndex == outline.templateInstructions.size() ? instructionIndex : -1;
    }

    @Override
    public void setUp() {
    }

    @Override
    public void clear() {
    }

    @Override
    public void buildPrelude(com.debughelper.tools.r8.ir.conversion.IRBuilder builder) {
      // Fill in the Argument instructions in the argument block.
      for (int i = 0; i < outline.arguments.size(); i++) {
        com.debughelper.tools.r8.ir.code.ValueType valueType = outline.arguments.get(i).outType();
        builder.addNonThisArgument(i, valueType);
      }
    }

    @Override
    public void buildPostlude(com.debughelper.tools.r8.ir.conversion.IRBuilder builder) {
      // Intentionally left empty. (Needed for Java-bytecode-frontend synchronization support.)
    }

    @Override
    public void buildInstruction(
            com.debughelper.tools.r8.ir.conversion.IRBuilder builder, int instructionIndex, boolean firstBlockInstruction) {
      if (instructionIndex == outline.templateInstructions.size()) {
        if (outline.returnType == dexItemFactory.voidType) {
          builder.addReturn();
        } else {
          builder.addReturn(ValueType.fromDexType(outline.returnType), outline.argumentCount());
        }
        return;
      }
      // Build IR from the template.
      com.debughelper.tools.r8.ir.code.Instruction template = outline.templateInstructions.get(instructionIndex);
      List<com.debughelper.tools.r8.ir.code.Value> inValues = new ArrayList<>(template.inValues().size());
      List<com.debughelper.tools.r8.ir.code.Value> templateInValues = template.inValues();
      // The template in-values are not re-ordered for commutative binary operations, as it does
      // not matter.
      for (int i = 0; i < templateInValues.size(); i++) {
        com.debughelper.tools.r8.ir.code.Value value = templateInValues.get(i);
        int register = outline.argumentMap.get(argumentMapIndex++);
        if (register == -1) {
          register = outline.argumentCount();
        }
        inValues.add(
            builder.readRegister(register, value.outType()));
      }
      com.debughelper.tools.r8.ir.code.Value outValue = null;
      if (template.outValue() != null) {
        Value value = template.outValue();
        outValue = builder
            .writeRegister(outline.argumentCount(), value.outType(), com.debughelper.tools.r8.ir.code.BasicBlock.ThrowingInfo.CAN_THROW);
      }

      Instruction newInstruction = null;
      if (template.isInvoke()) {
        com.debughelper.tools.r8.ir.code.Invoke templateInvoke = template.asInvoke();
        newInstruction = com.debughelper.tools.r8.ir.code.Invoke.createFromTemplate(templateInvoke, outValue, inValues);
      } else if (template.isAdd()) {
        com.debughelper.tools.r8.ir.code.Add templateInvoke = template.asAdd();
        newInstruction = new Add(
            templateInvoke.getNumericType(), outValue, inValues.get(0), inValues.get(1));
      } else if (template.isMul()) {
        com.debughelper.tools.r8.ir.code.Mul templateInvoke = template.asMul();
        newInstruction = new Mul(
            templateInvoke.getNumericType(), outValue, inValues.get(0), inValues.get(1));
      } else if (template.isSub()) {
        com.debughelper.tools.r8.ir.code.Sub templateInvoke = template.asSub();
        newInstruction = new Sub(
            templateInvoke.getNumericType(), outValue, inValues.get(0), inValues.get(1));
      } else if (template.isDiv()) {
        com.debughelper.tools.r8.ir.code.Div templateInvoke = template.asDiv();
        newInstruction = new Div(
            templateInvoke.getNumericType(), outValue, inValues.get(0), inValues.get(1));
      } else if (template.isRem()) {
        com.debughelper.tools.r8.ir.code.Rem templateInvoke = template.asRem();
        newInstruction = new Rem(
            templateInvoke.getNumericType(), outValue, inValues.get(0), inValues.get(1));
      } else {
        assert template.isNewInstance();
        com.debughelper.tools.r8.ir.code.NewInstance templateNewInstance = template.asNewInstance();
        newInstruction = new NewInstance(templateNewInstance.clazz, outValue);
      }
      builder.add(newInstruction);
    }

    @Override
    public void resolveAndBuildSwitch(int value, int fallthroughOffset, int payloadOffset,
        com.debughelper.tools.r8.ir.conversion.IRBuilder builder) {
      throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected call to resolveAndBuildSwitch");
    }

    @Override
    public void resolveAndBuildNewArrayFilledData(int arrayRef, int payloadOffset,
        com.debughelper.tools.r8.ir.conversion.IRBuilder builder) {
      throw new com.debughelper.tools.r8.errors.Unreachable("Unexpected call to resolveAndBuildNewArrayFilledData");
    }

    @Override
    public CatchHandlers<Integer> getCurrentCatchHandlers() {
      return null;
    }

    @Override
    public int getMoveExceptionRegister() {
      throw new com.debughelper.tools.r8.errors.Unreachable();
    }

    @Override
    public com.debughelper.tools.r8.ir.code.Position getDebugPositionAtOffset(int offset) {
      throw new com.debughelper.tools.r8.errors.Unreachable();
    }

    @Override
    public com.debughelper.tools.r8.ir.code.Position getCurrentPosition() {
      return Position.none();
    }

    @Override
    public boolean verifyCurrentInstructionCanThrow() {
      // TODO(sgjesse): Check more here?
      return true;
    }

    @Override
    public boolean verifyLocalInScope(DebugLocalInfo local) {
      return true;
    }

    @Override
    public boolean verifyRegister(int register) {
      return true;
    }
  }

  public class OutlineCode extends Code {

    private final Outline outline;

    OutlineCode(Outline outline) {
      this.outline = outline;
    }

    @Override
    public boolean isOutlineCode() {
      return true;
    }

    @Override
    public int estimatedSizeForInlining() {
      // We just onlined this, so do not inline it again.
      return Integer.MAX_VALUE;
    }

    @Override
    public OutlineCode asOutlineCode() {
      return this;
    }

    @Override
    public boolean isEmptyVoidMethod() {
      return false;
    }

    @Override
    public IRCode buildIR(
            com.debughelper.tools.r8.graph.DexEncodedMethod encodedMethod, AppInfo appInfo, com.debughelper.tools.r8.utils.InternalOptions options, Origin origin) {
      OutlineSourceCode source = new OutlineSourceCode(outline);
      com.debughelper.tools.r8.ir.conversion.IRBuilder builder = new IRBuilder(encodedMethod, appInfo, source, options);
      return builder.build();
    }

    @Override
    public String toString() {
      return outline.toString();
    }

    @Override
    public void registerCodeReferences(UseRegistry registry) {
      throw new Unreachable();
    }

    @Override
    protected int computeHashCode() {
      return outline.hashCode();
    }

    @Override
    protected boolean computeEquals(Object other) {
      return outline.equals(other);
    }

    @Override
    public String toString(DexEncodedMethod method, ClassNameMapper naming) {
      return null;
    }
  }
}
