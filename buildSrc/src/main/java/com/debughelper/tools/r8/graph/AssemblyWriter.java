// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.ir.conversion.IRConverter;
import com.debughelper.tools.r8.ir.conversion.OptimizationFeedback;
import com.debughelper.tools.r8.ir.conversion.OptimizationFeedbackIgnore;
import com.debughelper.tools.r8.naming.MemberNaming.FieldSignature;
import com.debughelper.tools.r8.ClassFileConsumer;
import com.debughelper.tools.r8.naming.ClassNameMapper;
import com.debughelper.tools.r8.naming.MemberNaming;
import com.debughelper.tools.r8.utils.CfgPrinter;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.debughelper.tools.r8.utils.Timing;

import java.io.PrintStream;

public class AssemblyWriter extends DexByteCodeWriter {

  private final boolean writeAllClassInfo;
  private final boolean writeFields;
  private final boolean writeAnnotations;
  private final boolean writeIR;
  private final AppInfoWithSubtyping appInfo;
  private final com.debughelper.tools.r8.utils.Timing timing = new Timing("AssemblyWriter");
  private final OptimizationFeedback ignoreOptimizationFeedback = new OptimizationFeedbackIgnore();

  public AssemblyWriter(
          DexApplication application, InternalOptions options, boolean allInfo, boolean writeIR) {
    super(application, options);
    this.writeAllClassInfo = allInfo;
    this.writeFields = allInfo;
    this.writeAnnotations = allInfo;
    this.writeIR = writeIR;
    if (writeIR) {
      this.appInfo = new AppInfoWithSubtyping(application.toDirect());
      if (options.programConsumer == null) {
        // Use class-file backend, since the CF frontend for testing does not support desugaring of
        // synchronized methods for the DEX backend (b/109789541).
        options.programConsumer = ClassFileConsumer.emptyConsumer();
      }
      options.outline.enabled = false;
    } else {
      this.appInfo = null;
    }
  }

  @Override
  String getFileEnding() {
    return ".dump";
  }

  @Override
  void writeClassHeader(DexProgramClass clazz, PrintStream ps) {
    String clazzName;
    if (application.getProguardMap() != null) {
      clazzName = application.getProguardMap().originalNameOf(clazz.type);
    } else {
      clazzName = clazz.type.toSourceString();
    }
    ps.println("# Bytecode for");
    ps.println("# Class: '" + clazzName + "'");
    if (writeAllClassInfo) {
      writeAnnotations(clazz.annotations, ps);
      ps.println("# Flags: '" + clazz.accessFlags + "'");
      if (clazz.superType != application.dexItemFactory.objectType) {
        ps.println("# Extends: '" + clazz.superType.toSourceString() + "'");
      }
      for (DexType value : clazz.interfaces.values) {
        ps.println("# Implements: '" + value.toSourceString() + "'");
      }
    }
    ps.println();
  }

  @Override
  void writeFieldsHeader(DexProgramClass clazz, PrintStream ps) {
    if (writeFields) {
      ps.println("#");
      ps.println("# Fields:");
      ps.println("#");
    }
  }

  @Override
  void writeField(DexEncodedField field, PrintStream ps) {
    if (writeFields) {
      com.debughelper.tools.r8.naming.ClassNameMapper naming = application.getProguardMap();
      MemberNaming.FieldSignature fieldSignature = naming != null
          ? naming.originalSignatureOf(field.field)
          : MemberNaming.FieldSignature.fromDexField(field.field);
      writeAnnotations(field.annotations, ps);
      ps.println(fieldSignature);
    }
  }

  @Override
  void writeFieldsFooter(DexProgramClass clazz, PrintStream ps) {
    ps.println();
  }

  @Override
  void writeMethod(DexEncodedMethod method, PrintStream ps) {
    ClassNameMapper naming = application.getProguardMap();
    String methodName = naming != null
        ? naming.originalSignatureOf(method.method).name
        : method.method.name.toString();
    ps.println("#");
    ps.println("# Method: '" + methodName + "':");
    writeAnnotations(method.annotations, ps);
    ps.println("#");
    ps.println();
    Code code = method.getCode();
    if (code != null) {
      if (writeIR) {
        writeIR(method, ps);
      } else {
        ps.println(code.toString(method, naming));
      }
    }
  }

  private void writeIR(DexEncodedMethod method, PrintStream ps) {
    com.debughelper.tools.r8.utils.CfgPrinter printer = new CfgPrinter();
    new IRConverter(appInfo, options, timing, printer)
        .processMethod(method, ignoreOptimizationFeedback, null, null, null);
    ps.println(printer.toString());
  }

  private void writeAnnotations(DexAnnotationSet annotations, PrintStream ps) {
    if (writeAnnotations) {
      if (!annotations.isEmpty()) {
        ps.println("# Annotations:");
        for (DexAnnotation annotation : annotations.annotations) {
          ps.print("#   ");
          ps.println(annotation);
        }
      }
    }
  }

  @Override
  void writeClassFooter(DexProgramClass clazz, PrintStream ps) {

  }
}
