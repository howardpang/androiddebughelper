package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.graph.DexDebugEvent.AdvanceLine;
import com.debughelper.tools.r8.graph.DexDebugEvent.AdvancePC;
import com.debughelper.tools.r8.graph.DexDebugEvent.Default;
import com.debughelper.tools.r8.graph.DexDebugEvent.EndLocal;
import com.debughelper.tools.r8.graph.DexDebugEvent.RestartLocal;
import com.debughelper.tools.r8.graph.DexDebugEvent.SetEpilogueBegin;
import com.debughelper.tools.r8.graph.DexDebugEvent.SetFile;
import com.debughelper.tools.r8.graph.DexDebugEvent.SetInlineFrame;
import com.debughelper.tools.r8.graph.DexDebugEvent.SetPrologueEnd;
import com.debughelper.tools.r8.graph.DexDebugEvent.StartLocal;
import com.debughelper.tools.r8.ir.code.Position;

/**
 * State machine to process and accumulate position-related DexDebugEvents. Clients should retrieve
 * the current state using the getters after a Default event.
 */
public class DexDebugPositionState implements DexDebugEventVisitor {
  private int currentPc = 0;
  private int currentLine;
  private DexString currentFile = null;
  private DexMethod currentMethod = null;
  private com.debughelper.tools.r8.ir.code.Position currentCallerPosition = null;

  public DexDebugPositionState(int startLine, DexMethod method) {
    currentLine = startLine;
    currentMethod = method;
  }

  @Override
  public void visit(AdvancePC advancePC) {
    assert advancePC.delta >= 0;
    currentPc += advancePC.delta;
  }

  @Override
  public void visit(AdvanceLine advanceLine) {
    currentLine += advanceLine.delta;
  }

  @Override
  public void visit(SetInlineFrame setInlineFrame) {
    currentMethod = setInlineFrame.callee;
    currentCallerPosition = setInlineFrame.caller;
  }

  @Override
  public void visit(Default defaultEvent) {
    assert defaultEvent.getPCDelta() >= 0;
    currentPc += defaultEvent.getPCDelta();
    currentLine += defaultEvent.getLineDelta();
  }

  @Override
  public void visit(SetFile setFile) {
    currentFile = setFile.fileName;
  }

  @Override
  public void visit(SetPrologueEnd setPrologueEnd) {
    // Empty.
  }

  @Override
  public void visit(SetEpilogueBegin setEpilogueBegin) {
    // Empty.
  }

  @Override
  public void visit(StartLocal startLocal) {
    // Empty.
  }

  @Override
  public void visit(EndLocal endLocal) {
    // Empty.
  }

  @Override
  public void visit(RestartLocal restartLocal) {
    // Empty.
  }

  public int getCurrentPc() {
    return currentPc;
  }

  public int getCurrentLine() {
    return currentLine;
  }

  public DexString getCurrentFile() {
    return currentFile;
  }

  public DexMethod getCurrentMethod() {
    return currentMethod;
  }

  public Position getCurrentCallerPosition() {
    return currentCallerPosition;
  }
}
