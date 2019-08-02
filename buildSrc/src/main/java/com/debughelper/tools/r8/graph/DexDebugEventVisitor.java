// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
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

public interface DexDebugEventVisitor {
  void visit(AdvancePC advancePC);

  void visit(AdvanceLine advanceLine);

  void visit(SetInlineFrame setInlineFrame);

  void visit(Default defaultEvent);

  void visit(SetFile setFile);

  void visit(SetPrologueEnd setPrologueEnd);

  void visit(SetEpilogueBegin setEpilogueBegin);

  void visit(StartLocal startLocal);

  void visit(EndLocal endLocal);

  void visit(RestartLocal restartLocal);
}
