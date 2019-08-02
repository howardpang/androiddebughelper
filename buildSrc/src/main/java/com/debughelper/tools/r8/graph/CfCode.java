// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.graph.AppInfo;
import com.debughelper.tools.r8.graph.DebugLocalInfo;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.ir.conversion.CfSourceCode;
import com.debughelper.tools.r8.ir.conversion.IRBuilder;
import com.debughelper.tools.r8.cf.CfPrinter;
import com.debughelper.tools.r8.cf.code.CfInstruction;
import com.debughelper.tools.r8.cf.code.CfLabel;
import com.debughelper.tools.r8.cf.code.CfPosition;
import com.debughelper.tools.r8.cf.code.CfReturnVoid;
import com.debughelper.tools.r8.cf.code.CfTryCatch;
import com.debughelper.tools.r8.errors.Unimplemented;
import com.debughelper.tools.r8.ir.code.IRCode;
import com.debughelper.tools.r8.ir.code.Position;
import com.debughelper.tools.r8.ir.code.ValueNumberGenerator;
import com.debughelper.tools.r8.naming.ClassNameMapper;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.utils.InternalOptions;

import java.util.Collections;
import java.util.List;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

public class CfCode extends Code {

  public static class LocalVariableInfo {
    private final int index;
    private final com.debughelper.tools.r8.graph.DebugLocalInfo local;
    private final com.debughelper.tools.r8.cf.code.CfLabel start;
    private com.debughelper.tools.r8.cf.code.CfLabel end;

    public LocalVariableInfo(int index, com.debughelper.tools.r8.graph.DebugLocalInfo local, com.debughelper.tools.r8.cf.code.CfLabel start) {
      this.index = index;
      this.local = local;
      this.start = start;
    }

    public LocalVariableInfo(int index, com.debughelper.tools.r8.graph.DebugLocalInfo local, com.debughelper.tools.r8.cf.code.CfLabel start, com.debughelper.tools.r8.cf.code.CfLabel end) {
      this(index, local, start);
      setEnd(end);
    }

    public void setEnd(com.debughelper.tools.r8.cf.code.CfLabel end) {
      assert this.end == null;
      assert end != null;
      this.end = end;
    }

    public int getIndex() {
      return index;
    }

    public com.debughelper.tools.r8.graph.DebugLocalInfo getLocal() {
      return local;
    }

    public com.debughelper.tools.r8.cf.code.CfLabel getStart() {
      return start;
    }

    public com.debughelper.tools.r8.cf.code.CfLabel getEnd() {
      return end;
    }
  }

  private final DexMethod method;
  private final int maxStack;
  private final int maxLocals;
  private final List<com.debughelper.tools.r8.cf.code.CfInstruction> instructions;
  private final List<com.debughelper.tools.r8.cf.code.CfTryCatch> tryCatchRanges;
  private final List<LocalVariableInfo> localVariables;

  public CfCode(
      DexMethod method,
      int maxStack,
      int maxLocals,
      List<com.debughelper.tools.r8.cf.code.CfInstruction> instructions,
      List<com.debughelper.tools.r8.cf.code.CfTryCatch> tryCatchRanges,
      List<LocalVariableInfo> localVariables) {
    this.method = method;
    this.maxStack = maxStack;
    this.maxLocals = maxLocals;
    this.instructions = instructions;
    this.tryCatchRanges = tryCatchRanges;
    this.localVariables = localVariables;
  }

  public DexMethod getMethod() {
    return method;
  }

  public int getMaxStack() {
    return maxStack;
  }

  public int getMaxLocals() {
    return maxLocals;
  }

  public List<com.debughelper.tools.r8.cf.code.CfTryCatch> getTryCatchRanges() {
    return tryCatchRanges;
  }

  public List<com.debughelper.tools.r8.cf.code.CfInstruction> getInstructions() {
    return Collections.unmodifiableList(instructions);
  }

  public List<LocalVariableInfo> getLocalVariables() {
    return Collections.unmodifiableList(localVariables);
  }

  @Override
  public int estimatedSizeForInlining() {
    return countNonStackOperations(Integer.MAX_VALUE);
  }

  @Override
  public boolean estimatedSizeForInliningAtMost(int threshold) {
    return countNonStackOperations(threshold) <= threshold;
  }

  private int countNonStackOperations(int threshold) {
    int result = 0;
    for (com.debughelper.tools.r8.cf.code.CfInstruction instruction : instructions) {
      if (instruction.emitsIR()) {
        result++;
        if (result > threshold) {
          break;
        }
      }
    }
    return result;
  }

  @Override
  public boolean isCfCode() {
    return true;
  }

  @Override
  public CfCode asCfCode() {
    return this;
  }

  public void write(MethodVisitor visitor, NamingLens namingLens) {
    for (com.debughelper.tools.r8.cf.code.CfInstruction instruction : instructions) {
      instruction.write(visitor, namingLens);
    }
    visitor.visitEnd();
    visitor.visitMaxs(maxStack, maxLocals);
    for (com.debughelper.tools.r8.cf.code.CfTryCatch tryCatch : tryCatchRanges) {
      Label start = tryCatch.start.getLabel();
      Label end = tryCatch.end.getLabel();
      for (int i = 0; i < tryCatch.guards.size(); i++) {
        com.debughelper.tools.r8.graph.DexType guard = tryCatch.guards.get(i);
        Label target = tryCatch.targets.get(i).getLabel();
        visitor.visitTryCatchBlock(
            start,
            end,
            target,
            guard == com.debughelper.tools.r8.graph.DexItemFactory.catchAllType ? null : namingLens.lookupInternalName(guard));
      }
    }
    for (LocalVariableInfo localVariable : localVariables) {
      DebugLocalInfo info = localVariable.local;
      visitor.visitLocalVariable(
          info.name.toString(),
          namingLens.lookupDescriptor(info.type).toString(),
          info.signature == null ? null : info.signature.toString(),
          localVariable.start.getLabel(),
          localVariable.end.getLabel(),
          localVariable.index);
    }
  }

  @Override
  protected int computeHashCode() {
    throw new com.debughelper.tools.r8.errors.Unimplemented();
  }

  @Override
  protected boolean computeEquals(Object other) {
    throw new com.debughelper.tools.r8.errors.Unimplemented();
  }

  @Override
  public boolean isEmptyVoidMethod() {
    for (com.debughelper.tools.r8.cf.code.CfInstruction insn : instructions) {
      if (!(insn instanceof CfReturnVoid)
          && !(insn instanceof CfLabel)
          && !(insn instanceof CfPosition)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public com.debughelper.tools.r8.ir.code.IRCode buildIR(
          com.debughelper.tools.r8.graph.DexEncodedMethod encodedMethod, com.debughelper.tools.r8.graph.AppInfo appInfo, com.debughelper.tools.r8.utils.InternalOptions options, com.debughelper.tools.r8.origin.Origin origin) {
    return internalBuild(encodedMethod, appInfo, options, null, null, origin);
  }

  @Override
  public com.debughelper.tools.r8.ir.code.IRCode buildInliningIR(
      com.debughelper.tools.r8.graph.DexEncodedMethod encodedMethod,
      com.debughelper.tools.r8.graph.AppInfo appInfo,
      com.debughelper.tools.r8.utils.InternalOptions options,
      com.debughelper.tools.r8.ir.code.ValueNumberGenerator valueNumberGenerator,
      com.debughelper.tools.r8.ir.code.Position callerPosition,
      com.debughelper.tools.r8.origin.Origin origin) {
    assert valueNumberGenerator != null;
    assert callerPosition != null;
    return internalBuild(
        encodedMethod, appInfo, options, valueNumberGenerator, callerPosition, origin);
  }

  private IRCode internalBuild(
      com.debughelper.tools.r8.graph.DexEncodedMethod encodedMethod,
      AppInfo appInfo,
      InternalOptions options,
      ValueNumberGenerator generator,
      Position callerPosition,
      Origin origin) {
    // TODO(b/109789541): Implement CF->IR->DEX for synchronized methods.
    if (options.isGeneratingDex() && encodedMethod.accessFlags.isSynchronized()) {
      throw new Unimplemented(
          "Converting CfCode to IR not supported for DEX output of synchronized methods.");
    }
    CfSourceCode source = new CfSourceCode(this, encodedMethod, callerPosition, origin);
    IRBuilder builder =
        (generator == null)
            ? new IRBuilder(encodedMethod, appInfo, source, options)
            : new IRBuilder(encodedMethod, appInfo, source, options, generator);
    return builder.build();
  }

  @Override
  public void registerCodeReferences(UseRegistry registry) {
    for (CfInstruction instruction : instructions) {
      instruction.registerUse(registry, method.holder);
    }
    for (CfTryCatch tryCatch : tryCatchRanges) {
      for (DexType guard : tryCatch.guards) {
        if (guard != DexItemFactory.catchAllType) {
          registry.registerTypeReference(guard);
        }
      }
    }
  }

  @Override
  public String toString() {
    return new com.debughelper.tools.r8.cf.CfPrinter(this, null).toString();
  }

  @Override
  public String toString(DexEncodedMethod method, ClassNameMapper naming) {
    return new CfPrinter(this, naming).toString();
  }
}
