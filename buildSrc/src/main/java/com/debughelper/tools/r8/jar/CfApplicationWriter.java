// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.jar;

//howard import static org.objectweb.asm.Opcodes.ASM6;
import static org.objectweb.asm.Opcodes.ASM5;

import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.graph.DexValue.DexValueAnnotation;
import com.debughelper.tools.r8.graph.DexValue.DexValueArray;
import com.debughelper.tools.r8.graph.DexValue.DexValueEnum;
import com.debughelper.tools.r8.graph.DexValue.DexValueField;
import com.debughelper.tools.r8.graph.DexValue.DexValueMethod;
import com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle;
import com.debughelper.tools.r8.graph.DexValue.DexValueMethodType;
import com.debughelper.tools.r8.graph.DexValue.DexValueString;
import com.debughelper.tools.r8.graph.DexValue.DexValueType;
import com.debughelper.tools.r8.graph.DexValue.UnknownDexValue;
import com.debughelper.tools.r8.ClassFileConsumer;
import com.debughelper.tools.r8.dex.ApplicationWriter;
import com.debughelper.tools.r8.errors.Unimplemented;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.graph.Code;
import com.debughelper.tools.r8.graph.DexAnnotation;
import com.debughelper.tools.r8.graph.DexAnnotationElement;
import com.debughelper.tools.r8.graph.DexAnnotationSet;
import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.graph.DexEncodedAnnotation;
import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexProgramClass;
import com.debughelper.tools.r8.graph.DexString;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.InnerClassAttribute;
import com.debughelper.tools.r8.graph.JarClassFileReader;
import com.debughelper.tools.r8.graph.ParameterAnnotationsList;
import com.debughelper.tools.r8.naming.NamingLens;
import com.debughelper.tools.r8.naming.ProguardMapSupplier;
import com.debughelper.tools.r8.utils.ExceptionUtils;
import com.debughelper.tools.r8.utils.InternalOptions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.CheckClassAdapter;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

public class CfApplicationWriter {
  private static final boolean RUN_VERIFIER = false;
  private static final boolean PRINT_CF = false;

  private final com.debughelper.tools.r8.graph.DexApplication application;
  private final com.debughelper.tools.r8.naming.NamingLens namingLens;
  private final com.debughelper.tools.r8.utils.InternalOptions options;

  public final com.debughelper.tools.r8.naming.ProguardMapSupplier proguardMapSupplier;
  public final String deadCode;
  public final String proguardSeedsData;

  public CfApplicationWriter(
      DexApplication application,
      InternalOptions options,
      String deadCode,
      NamingLens namingLens,
      String proguardSeedsData,
      ProguardMapSupplier proguardMapSupplier) {
    this.application = application;
    this.namingLens = namingLens;
    this.options = options;
    this.proguardMapSupplier = proguardMapSupplier;
    this.deadCode = deadCode;
    this.proguardSeedsData = proguardSeedsData;
  }

  public void write(com.debughelper.tools.r8.ClassFileConsumer consumer, ExecutorService executor) throws IOException {
    application.timing.begin("CfApplicationWriter.write");
    try {
      writeApplication(consumer, executor);
    } finally {
      application.timing.end();
    }
  }

  private void writeApplication(com.debughelper.tools.r8.ClassFileConsumer consumer, ExecutorService executor)
      throws IOException {
    for (com.debughelper.tools.r8.graph.DexProgramClass clazz : application.classes()) {
      if (clazz.getSynthesizedFrom().isEmpty()) {
        writeClass(clazz, consumer);
      } else {
        throw new Unimplemented("No support for synthetics in the Java bytecode backend.");
      }
    }
    ApplicationWriter.supplyAdditionalConsumers(
        application, namingLens, options, deadCode, proguardMapSupplier, proguardSeedsData);
  }

  private void writeClass(com.debughelper.tools.r8.graph.DexProgramClass clazz, ClassFileConsumer consumer) throws IOException {
    ClassWriter writer = new ClassWriter(0);
    writer.visitSource(clazz.sourceFile != null ? clazz.sourceFile.toString() : null, null);
    int version = getClassFileVersion(clazz);
    int access = clazz.accessFlags.getAsCfAccessFlags();
    String desc = namingLens.lookupDescriptor(clazz.type).toString();
    String name = namingLens.lookupInternalName(clazz.type);
    String signature = getSignature(clazz.annotations);
    String superName =
        clazz.type == options.itemFactory.objectType
            ? null
            : namingLens.lookupInternalName(clazz.superType);
    String[] interfaces = new String[clazz.interfaces.values.length];
    for (int i = 0; i < clazz.interfaces.values.length; i++) {
      interfaces[i] = namingLens.lookupInternalName(clazz.interfaces.values[i]);
    }
    writer.visit(version, access, name, signature, superName, interfaces);
    writeAnnotations(writer::visitAnnotation, clazz.annotations.annotations);
    ImmutableMap<com.debughelper.tools.r8.graph.DexString, com.debughelper.tools.r8.graph.DexValue> defaults = getAnnotationDefaults(clazz.annotations);

    if (clazz.getEnclosingMethod() != null) {
      clazz.getEnclosingMethod().write(writer, namingLens);
    }

    for (InnerClassAttribute entry : clazz.getInnerClasses()) {
      entry.write(writer, namingLens);
    }

    for (com.debughelper.tools.r8.graph.DexEncodedField field : clazz.staticFields()) {
      writeField(field, writer);
    }
    for (com.debughelper.tools.r8.graph.DexEncodedField field : clazz.instanceFields()) {
      writeField(field, writer);
    }
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : clazz.directMethods()) {
      writeMethod(method, writer, defaults);
    }
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : clazz.virtualMethods()) {
      writeMethod(method, writer, defaults);
    }
    writer.visitEnd();

    byte[] result = writer.toByteArray();
    if (PRINT_CF) {
      System.out.print(printCf(result));
      System.out.flush();
    }
    if (RUN_VERIFIER) {
      // Generally, this will fail with ClassNotFoundException,
      // so don't assert that verifyCf() returns true.
      verifyCf(result);
    }
    ExceptionUtils.withConsumeResourceHandler(
        options.reporter, handler -> consumer.accept(result, desc, handler));
  }

  private int getClassFileVersion(DexProgramClass clazz) {
    int version = clazz.getClassFileVersion();
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : clazz.directMethods()) {
      version = Math.max(version, method.getClassFileVersion());
    }
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : clazz.virtualMethods()) {
      version = Math.max(version, method.getClassFileVersion());
    }
    return version;
  }

  private com.debughelper.tools.r8.graph.DexValue getSystemAnnotationValue(com.debughelper.tools.r8.graph.DexAnnotationSet annotations, DexType type) {
    com.debughelper.tools.r8.graph.DexAnnotation annotation = annotations.getFirstMatching(type);
    if (annotation == null) {
      return null;
    }
    assert annotation.visibility == com.debughelper.tools.r8.graph.DexAnnotation.VISIBILITY_SYSTEM;
    com.debughelper.tools.r8.graph.DexEncodedAnnotation encodedAnnotation = annotation.annotation;
    assert encodedAnnotation.elements.length == 1;
    return encodedAnnotation.elements[0].value;
  }

  private String getSignature(com.debughelper.tools.r8.graph.DexAnnotationSet annotations) {
    com.debughelper.tools.r8.graph.DexValue.DexValueArray value =
        (com.debughelper.tools.r8.graph.DexValue.DexValueArray)
            getSystemAnnotationValue(annotations, application.dexItemFactory.annotationSignature);
    if (value == null) {
      return null;
    }
    // Signature has already been minified by ClassNameMinifier.renameTypesInGenericSignatures().
    com.debughelper.tools.r8.graph.DexValue[] parts = value.getValues();
    StringBuilder res = new StringBuilder();
    for (com.debughelper.tools.r8.graph.DexValue part : parts) {
      res.append(((com.debughelper.tools.r8.graph.DexValue.DexValueString) part).getValue().toString());
    }
    return res.toString();
  }

  private ImmutableMap<com.debughelper.tools.r8.graph.DexString, com.debughelper.tools.r8.graph.DexValue> getAnnotationDefaults(com.debughelper.tools.r8.graph.DexAnnotationSet annotations) {
    com.debughelper.tools.r8.graph.DexValue.DexValueAnnotation value =
        (com.debughelper.tools.r8.graph.DexValue.DexValueAnnotation)
            getSystemAnnotationValue(annotations, application.dexItemFactory.annotationDefault);
    if (value == null) {
      return ImmutableMap.of();
    }
    com.debughelper.tools.r8.graph.DexEncodedAnnotation annotation = value.value;
    Builder<com.debughelper.tools.r8.graph.DexString, com.debughelper.tools.r8.graph.DexValue> builder = ImmutableMap.builder();
    for (com.debughelper.tools.r8.graph.DexAnnotationElement element : annotation.elements) {
      builder.put(element.name, element.value);
    }
    return builder.build();
  }

  private String[] getExceptions(DexAnnotationSet annotations) {
    com.debughelper.tools.r8.graph.DexValue.DexValueArray value =
        (com.debughelper.tools.r8.graph.DexValue.DexValueArray)
            getSystemAnnotationValue(annotations, application.dexItemFactory.annotationThrows);
    if (value == null) {
      return null;
    }
    com.debughelper.tools.r8.graph.DexValue[] values = value.getValues();
    String[] res = new String[values.length];
    for (int i = 0; i < values.length; i++) {
      res[i] = namingLens.lookupInternalName(((com.debughelper.tools.r8.graph.DexValue.DexValueType) values[i]).value);
    }
    return res;
  }

  private Object getStaticValue(com.debughelper.tools.r8.graph.DexEncodedField field) {
    if (!field.accessFlags.isStatic() || !field.hasExplicitStaticValue()) {
      return null;
    }
    return field.getStaticValue().asAsmEncodedObject();
  }

  private void writeField(DexEncodedField field, ClassWriter writer) {
    int access = field.accessFlags.getAsCfAccessFlags();
    String name = namingLens.lookupName(field.field).toString();
    String desc = namingLens.lookupDescriptor(field.field.type).toString();
    String signature = getSignature(field.annotations);
    Object value = getStaticValue(field);
    FieldVisitor visitor = writer.visitField(access, name, desc, signature, value);
    writeAnnotations(visitor::visitAnnotation, field.annotations.annotations);
    visitor.visitEnd();
  }

  private void writeMethod(
          DexEncodedMethod method, ClassWriter writer, ImmutableMap<DexString, com.debughelper.tools.r8.graph.DexValue> defaults) {
    int access = method.accessFlags.getAsCfAccessFlags();
    String name = namingLens.lookupName(method.method).toString();
    String desc = method.descriptor(namingLens);
    String signature = getSignature(method.annotations);
    String[] exceptions = getExceptions(method.annotations);
    MethodVisitor visitor = writer.visitMethod(access, name, desc, signature, exceptions);
    if (defaults.containsKey(method.method.name)) {
      AnnotationVisitor defaultVisitor = visitor.visitAnnotationDefault();
      if (defaultVisitor != null) {
        writeAnnotationElement(defaultVisitor, null, defaults.get(method.method.name));
        defaultVisitor.visitEnd();
      }
    }
    writeAnnotations(visitor::visitAnnotation, method.annotations.annotations);
    writeParameterAnnotations(visitor, method.parameterAnnotationsList);
    if (!method.accessFlags.isAbstract() && !method.accessFlags.isNative()) {
      writeCode(method.getCode(), visitor);
    }
    visitor.visitEnd();
  }

  private void writeParameterAnnotations(
      MethodVisitor visitor, ParameterAnnotationsList parameterAnnotations) {
    for (int i = 0; i < parameterAnnotations.size(); i++) {
      if (parameterAnnotations.isMissing(i)) {
        AnnotationVisitor av =
            visitor.visitParameterAnnotation(i, JarClassFileReader.SYNTHETIC_ANNOTATION, false);
        if (av != null) {
          av.visitEnd();
        }
      } else {
        int iFinal = i;
        writeAnnotations(
            (d, vis) -> visitor.visitParameterAnnotation(iFinal, d, vis),
            parameterAnnotations.get(i).annotations);
      }
    }
  }

  private interface AnnotationConsumer {
    AnnotationVisitor visit(String desc, boolean visible);
  }

  private void writeAnnotations(AnnotationConsumer visitor, com.debughelper.tools.r8.graph.DexAnnotation[] annotations) {
    for (com.debughelper.tools.r8.graph.DexAnnotation dexAnnotation : annotations) {
      if (dexAnnotation.visibility == com.debughelper.tools.r8.graph.DexAnnotation.VISIBILITY_SYSTEM) {
        // Annotations with VISIBILITY_SYSTEM are not annotations in CF, but are special
        // annotations in Dex, i.e. default, enclosing class, enclosing method, member classes,
        // signature, throws.
        continue;
      }
      AnnotationVisitor v =
          visitor.visit(
              namingLens.lookupDescriptor(dexAnnotation.annotation.type).toString(),
              dexAnnotation.visibility == DexAnnotation.VISIBILITY_RUNTIME);
      if (v != null) {
        writeAnnotation(v, dexAnnotation.annotation);
        v.visitEnd();
      }
    }
  }

  private void writeAnnotation(AnnotationVisitor v, DexEncodedAnnotation annotation) {
    for (DexAnnotationElement element : annotation.elements) {
      writeAnnotationElement(v, element.name.toString(), element.value);
    }
  }

  private void writeAnnotationElement(AnnotationVisitor visitor, String name, com.debughelper.tools.r8.graph.DexValue value) {
    if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueAnnotation) {
      com.debughelper.tools.r8.graph.DexValue.DexValueAnnotation valueAnnotation = (com.debughelper.tools.r8.graph.DexValue.DexValueAnnotation) value;
      AnnotationVisitor innerVisitor =
          visitor.visitAnnotation(
              name, namingLens.lookupDescriptor(valueAnnotation.value.type).toString());
      if (innerVisitor != null) {
        writeAnnotation(innerVisitor, valueAnnotation.value);
        innerVisitor.visitEnd();
      }
    } else if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueArray) {
      com.debughelper.tools.r8.graph.DexValue[] values = ((com.debughelper.tools.r8.graph.DexValue.DexValueArray) value).getValues();
      AnnotationVisitor innerVisitor = visitor.visitArray(name);
      if (innerVisitor != null) {
        for (com.debughelper.tools.r8.graph.DexValue arrayValue : values) {
          writeAnnotationElement(innerVisitor, null, arrayValue);
        }
        innerVisitor.visitEnd();
      }
    } else if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueEnum) {
      com.debughelper.tools.r8.graph.DexValue.DexValueEnum en = (com.debughelper.tools.r8.graph.DexValue.DexValueEnum) value;
      visitor.visitEnum(
          name, namingLens.lookupDescriptor(en.value.type).toString(), en.value.name.toString());
    } else if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueField) {
      throw new com.debughelper.tools.r8.errors.Unreachable("writeAnnotationElement of DexValueField");
    } else if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueMethod) {
      throw new com.debughelper.tools.r8.errors.Unreachable("writeAnnotationElement of DexValueMethod");
    } else if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueMethodHandle) {
      throw new com.debughelper.tools.r8.errors.Unreachable("writeAnnotationElement of DexValueMethodHandle");
    } else if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueMethodType) {
      throw new com.debughelper.tools.r8.errors.Unreachable("writeAnnotationElement of DexValueMethodType");
    } else if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueString) {
      com.debughelper.tools.r8.graph.DexValue.DexValueString str = (com.debughelper.tools.r8.graph.DexValue.DexValueString) value;
      visitor.visit(name, str.getValue().toString());
    } else if (value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueType) {
      com.debughelper.tools.r8.graph.DexValue.DexValueType ty = (com.debughelper.tools.r8.graph.DexValue.DexValueType) value;
      visitor.visit(name, Type.getType(namingLens.lookupDescriptor(ty.value).toString()));
    } else if (value instanceof com.debughelper.tools.r8.graph.DexValue.UnknownDexValue) {
      throw new Unreachable("writeAnnotationElement of UnknownDexValue");
    } else {
      visitor.visit(name, value.getBoxedValue());
    }
  }

  private void writeCode(Code code, MethodVisitor visitor) {
    if (code.isJarCode()) {
      assert namingLens.isIdentityLens();
      code.asJarCode().writeTo(visitor);
    } else {
      assert code.isCfCode();
      code.asCfCode().write(visitor, namingLens);
    }
  }

  public static String printCf(byte[] result) {
    ClassReader reader = new ClassReader(result);
    ClassNode node = new ClassNode(ASM5);
    reader.accept(node, ASM5);
    //howard
    StringWriter writer = new StringWriter();
    List<MethodNode> methodNodes = node.methods;
    for (MethodNode method : methodNodes) {
      writer.append(method.name).append(method.desc).append('\n');
      TraceMethodVisitor visitor = new TraceMethodVisitor(new Textifier());
      method.accept(visitor);
      visitor.p.print(new PrintWriter(writer));
      writer.append('\n');
    }
    return writer.toString();
  }

  private static void verifyCf(byte[] result) {
    ClassReader reader = new ClassReader(result);
    PrintWriter pw = new PrintWriter(System.out);
    CheckClassAdapter.verify(reader, false, pw);
  }
}
