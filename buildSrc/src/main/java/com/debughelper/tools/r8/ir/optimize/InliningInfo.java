// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.ir.optimize;

import com.debughelper.tools.r8.ir.code.Invoke.Type;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.ir.code.Invoke;
import com.debughelper.tools.r8.ir.code.InvokeMethod;

import java.util.ArrayList;
import java.util.List;


// Class for collecting inlining information for one compiled DexEncodedMethod.
public class InliningInfo {

  static class Edge {
    final Invoke.Type type;
    final com.debughelper.tools.r8.graph.DexMethod declared;
    final Node inlinee;

    public Edge(Invoke.Type type, DexMethod declared, Node inlinee) {
      this.type = type;
      this.declared = declared;
      this.inlinee = inlinee;
    }

    void appendOn(StringBuffer buffer) {
      if (declared != null) {
        buffer.append(declared.toSourceString());
        buffer.append(' ');
      }
      inlinee.appendOn(buffer);
    }
  }

  static abstract class Node {
    abstract void appendOn(StringBuffer buffer);
  }

  static class Inlining extends Node {
    final com.debughelper.tools.r8.graph.DexEncodedMethod target;

    Inlining(com.debughelper.tools.r8.graph.DexEncodedMethod target) {
      this.target = target;
    }

    @Override
    void appendOn(StringBuffer buffer) {
      buffer.append("<< INLINED");
    }
  }

  static class NotInlining extends Node {

    final String reason;

    NotInlining(String reason) {
      this.reason = reason;
    }

    @Override
    public void appendOn(StringBuffer buffer) {
      buffer.append("-- no inlining: ");
      buffer.append(reason);
    }
  }

  final com.debughelper.tools.r8.graph.DexEncodedMethod method;
  final List<Edge> edges = new ArrayList<>();

  public InliningInfo(com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    this.method = method;
  }

  public void include(Invoke.Type type, DexEncodedMethod target) {
    edges.add(new Edge(type, target.method, new Inlining(target)));
  }

  public void exclude(InvokeMethod invoke, String reason) {
    edges.add(new Edge(invoke.getType(), invoke.getInvokedMethod(), new NotInlining(reason)));
  }

  @Override
  public String toString() {
    StringBuffer buffer = new StringBuffer(method.method.toSourceString());
    buffer.append(" {\n");
    for (Edge edge : edges) {
      buffer.append("  ");
      edge.appendOn(buffer);
      buffer.append(".\n");
    }
    buffer.append("}\n");
    return buffer.toString();
  }
}
