// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.debughelper.tools.r8.ir.desugar;

import com.debughelper.tools.r8.errors.CompilationError;
import com.debughelper.tools.r8.graph.DexAnnotation;
import com.debughelper.tools.r8.graph.DexAnnotationElement;
import com.debughelper.tools.r8.graph.DexApplication;
import com.debughelper.tools.r8.graph.DexClass;
import com.debughelper.tools.r8.graph.DexEncodedAnnotation;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DexProto;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.graph.DexValue;
import com.debughelper.tools.r8.graph.MethodAccessFlags;
import com.debughelper.tools.r8.ir.code.Invoke;
import com.debughelper.tools.r8.ir.conversion.IRConverter;
import com.debughelper.tools.r8.ir.synthetic.ForwardMethodSourceCode;
import com.debughelper.tools.r8.ir.synthetic.SynthesizedCode;
import com.google.common.base.Predicates;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

// Responsible for processing the annotations dalvik.annotation.codegen.CovariantReturnType and
// dalvik.annotation.codegen.CovariantReturnType$CovariantReturnTypes.
//
// Consider the following class:
//   public class B extends A {
//     @CovariantReturnType(returnType = B.class, presentAfter = 25)
//     @Override
//     public A m(...) { ... return new B(); }
//   }
//
// The annotation is used to indicate that the compiler should insert a synthetic method that is
// equivalent to method m, but has return type B instead of A. Thus, for this example, this
// component is responsible for inserting the following method in class B (in addition to the
// existing method m):
//   public B m(...) { A result = "invoke B.m(...)A;"; return (B) result; }
//
// Note that a method may be annotated with more than one CovariantReturnType annotation. In this
// case there will be a CovariantReturnType$CovariantReturnTypes annotation on the method that wraps
// several CovariantReturnType annotations. In this case, a new method is synthesized for each of
// the contained CovariantReturnType annotations.
public final class CovariantReturnTypeAnnotationTransformer {
  private final com.debughelper.tools.r8.ir.conversion.IRConverter converter;
  private final com.debughelper.tools.r8.graph.DexItemFactory factory;

  public CovariantReturnTypeAnnotationTransformer(IRConverter converter, DexItemFactory factory) {
    this.converter = converter;
    this.factory = factory;
  }

  public void process(DexApplication.Builder<?> builder) {
    // List of methods that should be added to the next class.
    List<com.debughelper.tools.r8.graph.DexEncodedMethod> methodsWithCovariantReturnTypeAnnotation = new LinkedList<>();
    List<com.debughelper.tools.r8.graph.DexEncodedMethod> covariantReturnTypeMethods = new LinkedList<>();
    for (com.debughelper.tools.r8.graph.DexClass clazz : builder.getProgramClasses()) {
      // Construct the methods that should be added to clazz.
      buildCovariantReturnTypeMethodsForClass(
          clazz, methodsWithCovariantReturnTypeAnnotation, covariantReturnTypeMethods);
      if (covariantReturnTypeMethods.isEmpty()) {
        continue;
      }
      updateClass(clazz, methodsWithCovariantReturnTypeAnnotation, covariantReturnTypeMethods);
      // Reset lists for the next class that will have a CovariantReturnType or
      // CovariantReturnType$CovariantReturnTypes annotation.
      methodsWithCovariantReturnTypeAnnotation.clear();
      covariantReturnTypeMethods.clear();
    }
  }

  private void updateClass(
      com.debughelper.tools.r8.graph.DexClass clazz,
      List<com.debughelper.tools.r8.graph.DexEncodedMethod> methodsWithCovariantReturnTypeAnnotation,
      List<com.debughelper.tools.r8.graph.DexEncodedMethod> covariantReturnTypeMethods) {
    // It is a compilation error if the class already has a method with a signature similar to one
    // of the methods in covariantReturnTypeMethods.
    for (com.debughelper.tools.r8.graph.DexEncodedMethod syntheticMethod : covariantReturnTypeMethods) {
      if (hasVirtualMethodWithSignature(clazz, syntheticMethod)) {
        throw new com.debughelper.tools.r8.errors.CompilationError(
            String.format(
                "Cannot process CovariantReturnType annotation: Class %s already "
                    + "has a method \"%s\"",
                clazz.getType(), syntheticMethod.toSourceString()));
      }
    }
    // Remove the CovariantReturnType annotations.
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : methodsWithCovariantReturnTypeAnnotation) {
      method.annotations =
          method.annotations.keepIf(x -> !isCovariantReturnTypeAnnotation(x.annotation));
    }
    // Add the newly constructed methods to the class.
    com.debughelper.tools.r8.graph.DexEncodedMethod[] oldVirtualMethods = clazz.virtualMethods();
    com.debughelper.tools.r8.graph.DexEncodedMethod[] newVirtualMethods =
        new com.debughelper.tools.r8.graph.DexEncodedMethod[oldVirtualMethods.length + covariantReturnTypeMethods.size()];
    System.arraycopy(oldVirtualMethods, 0, newVirtualMethods, 0, oldVirtualMethods.length);
    int i = oldVirtualMethods.length;
    for (com.debughelper.tools.r8.graph.DexEncodedMethod syntheticMethod : covariantReturnTypeMethods) {
      newVirtualMethods[i] = syntheticMethod;
      i++;
    }
    clazz.setVirtualMethods(newVirtualMethods);
  }

  // Processes all the dalvik.annotation.codegen.CovariantReturnType and dalvik.annotation.codegen.
  // CovariantReturnTypes annotations in the given DexClass. Adds the newly constructed, synthetic
  // methods to the list covariantReturnTypeMethods.
  private void buildCovariantReturnTypeMethodsForClass(
      com.debughelper.tools.r8.graph.DexClass clazz,
      List<com.debughelper.tools.r8.graph.DexEncodedMethod> methodsWithCovariantReturnTypeAnnotation,
      List<com.debughelper.tools.r8.graph.DexEncodedMethod> covariantReturnTypeMethods) {
    for (com.debughelper.tools.r8.graph.DexEncodedMethod method : clazz.virtualMethods()) {
      if (methodHasCovariantReturnTypeAnnotation(method)) {
        methodsWithCovariantReturnTypeAnnotation.add(method);
        buildCovariantReturnTypeMethodsForMethod(clazz, method, covariantReturnTypeMethods);
      }
    }
  }

  private boolean methodHasCovariantReturnTypeAnnotation(com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    for (com.debughelper.tools.r8.graph.DexAnnotation annotation : method.annotations.annotations) {
      if (isCovariantReturnTypeAnnotation(annotation.annotation)) {
        return true;
      }
    }
    return false;
  }

  // Processes all the dalvik.annotation.codegen.CovariantReturnType and dalvik.annotation.Co-
  // variantReturnTypes annotations on the given method. Adds the newly constructed, synthetic
  // methods to the list covariantReturnTypeMethods.
  private void buildCovariantReturnTypeMethodsForMethod(
          com.debughelper.tools.r8.graph.DexClass clazz, com.debughelper.tools.r8.graph.DexEncodedMethod method, List<com.debughelper.tools.r8.graph.DexEncodedMethod> covariantReturnTypeMethods) {
    assert methodHasCovariantReturnTypeAnnotation(method);
    for (com.debughelper.tools.r8.graph.DexType covariantReturnType : getCovariantReturnTypes(clazz, method)) {
      com.debughelper.tools.r8.graph.DexEncodedMethod covariantReturnTypeMethod =
          buildCovariantReturnTypeMethod(clazz, method, covariantReturnType);
      covariantReturnTypeMethods.add(covariantReturnTypeMethod);
    }
  }

  // Builds a synthetic method that invokes the given method, casts the result to
  // covariantReturnType, and then returns the result. The newly created method will have return
  // type covariantReturnType.
  //
  // Note: any "synchronized" or "strictfp" modifier could be dropped safely.
  private com.debughelper.tools.r8.graph.DexEncodedMethod buildCovariantReturnTypeMethod(
          com.debughelper.tools.r8.graph.DexClass clazz, com.debughelper.tools.r8.graph.DexEncodedMethod method, com.debughelper.tools.r8.graph.DexType covariantReturnType) {
    DexProto newProto =
        factory.createProto(
            covariantReturnType, method.method.proto.shorty, method.method.proto.parameters);
    MethodAccessFlags newAccessFlags = method.accessFlags.copy();
    newAccessFlags.setBridge();
    newAccessFlags.setSynthetic();
    com.debughelper.tools.r8.graph.DexEncodedMethod newVirtualMethod =
        new com.debughelper.tools.r8.graph.DexEncodedMethod(
            factory.createMethod(method.method.holder, newProto, method.method.name),
            newAccessFlags,
            method.annotations.keepIf(x -> !isCovariantReturnTypeAnnotation(x.annotation)),
            method.parameterAnnotationsList.keepIf(Predicates.alwaysTrue()),
            new SynthesizedCode(
                new ForwardMethodSourceCode(
                    clazz.type,
                    newProto,
                    method.method.holder,
                    method.method,
                    Invoke.Type.VIRTUAL,
                    true)));
    // Optimize to generate DexCode instead of SynthesizedCode.
    converter.optimizeSynthesizedMethod(newVirtualMethod);
    return newVirtualMethod;
  }

  // Returns the set of covariant return types for method.
  //
  // If the method is:
  //   @dalvik.annotation.codegen.CovariantReturnType(returnType=SubOfFoo, presentAfter=25)
  //   @dalvik.annotation.codegen.CovariantReturnType(returnType=SubOfSubOfFoo, presentAfter=28)
  //   @Override
  //   public Foo foo() { ... return new SubOfSubOfFoo(); }
  // then this method returns the set { SubOfFoo, SubOfSubOfFoo }.
  private Set<com.debughelper.tools.r8.graph.DexType> getCovariantReturnTypes(com.debughelper.tools.r8.graph.DexClass clazz, com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    Set<com.debughelper.tools.r8.graph.DexType> covariantReturnTypes = new HashSet<>();
    for (DexAnnotation annotation : method.annotations.annotations) {
      if (isCovariantReturnTypeAnnotation(annotation.annotation)) {
        getCovariantReturnTypesFromAnnotation(
            clazz, method, annotation.annotation, covariantReturnTypes);
      }
    }
    return covariantReturnTypes;
  }

  private void getCovariantReturnTypesFromAnnotation(
      com.debughelper.tools.r8.graph.DexClass clazz,
      com.debughelper.tools.r8.graph.DexEncodedMethod method,
      com.debughelper.tools.r8.graph.DexEncodedAnnotation annotation,
      Set<DexType> covariantReturnTypes) {
    assert isCovariantReturnTypeAnnotation(annotation);
    boolean hasPresentAfterElement = false;
    for (DexAnnotationElement element : annotation.elements) {
      String name = element.name.toString();
      if (annotation.type == factory.annotationCovariantReturnType) {
        if (name.equals("returnType")) {
          if (!(element.value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueType)) {
            throw new com.debughelper.tools.r8.errors.CompilationError(
                String.format(
                    "Expected element \"returnType\" of CovariantReturnType annotation to "
                        + "reference a type (method: \"%s\", was: %s)",
                    method.toSourceString(), element.value.getClass().getCanonicalName()));
          }

          com.debughelper.tools.r8.graph.DexValue.DexValueType dexValueType = (com.debughelper.tools.r8.graph.DexValue.DexValueType) element.value;
          covariantReturnTypes.add(dexValueType.value);
        } else if (name.equals("presentAfter")) {
          hasPresentAfterElement = true;
        }
      } else {
        if (name.equals("value")) {
          if (!(element.value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueArray)) {
            throw new com.debughelper.tools.r8.errors.CompilationError(
                String.format(
                    "Expected element \"value\" of CovariantReturnTypes annotation to "
                        + "be an array (method: \"%s\", was: %s)",
                    method.toSourceString(), element.value.getClass().getCanonicalName()));
          }

          com.debughelper.tools.r8.graph.DexValue.DexValueArray array = (com.debughelper.tools.r8.graph.DexValue.DexValueArray) element.value;
          // Handle the inner dalvik.annotation.codegen.CovariantReturnType annotations recursively.
          for (com.debughelper.tools.r8.graph.DexValue value : array.getValues()) {
            assert value instanceof com.debughelper.tools.r8.graph.DexValue.DexValueAnnotation;
            com.debughelper.tools.r8.graph.DexValue.DexValueAnnotation innerAnnotation = (DexValue.DexValueAnnotation) value;
            getCovariantReturnTypesFromAnnotation(
                clazz, method, innerAnnotation.value, covariantReturnTypes);
          }
        }
      }
    }

    if (annotation.type == factory.annotationCovariantReturnType && !hasPresentAfterElement) {
      throw new CompilationError(
          String.format(
              "CovariantReturnType annotation for method \"%s\" is missing mandatory element "
                  + "\"presentAfter\" (class %s)",
              clazz.getType(), method.toSourceString()));
    }
  }

  private boolean isCovariantReturnTypeAnnotation(DexEncodedAnnotation annotation) {
    return annotation.type == factory.annotationCovariantReturnType
        || annotation.type == factory.annotationCovariantReturnTypes;
  }

  private static boolean hasVirtualMethodWithSignature(DexClass clazz, com.debughelper.tools.r8.graph.DexEncodedMethod method) {
    for (DexEncodedMethod existingMethod : clazz.virtualMethods()) {
      if (existingMethod.method.equals(method.method)) {
        return true;
      }
    }
    return false;
  }
}
