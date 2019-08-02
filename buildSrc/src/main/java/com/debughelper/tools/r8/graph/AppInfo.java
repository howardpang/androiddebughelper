// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.graph;

import com.debughelper.tools.r8.graph.DexEncodedField;
import com.debughelper.tools.r8.graph.DexEncodedMethod;
import com.debughelper.tools.r8.graph.DexItemFactory;
import com.debughelper.tools.r8.graph.DirectMappedDexApplication;
import com.debughelper.tools.r8.graph.GraphLense;
import com.debughelper.tools.r8.graph.KeyedDexItem;
import com.debughelper.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.debughelper.tools.r8.errors.Unreachable;
import com.debughelper.tools.r8.origin.Origin;
import com.debughelper.tools.r8.shaking.Enqueuer;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Sets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class AppInfo {

  public final DexApplication app;
  public final DexItemFactory dexItemFactory;
  private final ConcurrentHashMap<DexType, Map<Descriptor<?,?>, com.debughelper.tools.r8.graph.KeyedDexItem<?>>> definitions =
      new ConcurrentHashMap<>();

  public AppInfo(DexApplication application) {
    this.app = application;
    this.dexItemFactory = app.dexItemFactory;
  }

  protected AppInfo(AppInfo previous) {
    this.app = previous.app;
    this.dexItemFactory = app.dexItemFactory;
    this.definitions.putAll(previous.definitions);
  }

  protected AppInfo(DirectMappedDexApplication application, GraphLense lense) {
    // Rebuild information from scratch, as the application object has changed. We do not
    // use the lense here, as it is about applied occurrences and not definitions.
    // In particular, we have to invalidate the definitions cache, as its keys are no longer
    // valid.
    this(application);
  }

  private Map<Descriptor<?,?>, com.debughelper.tools.r8.graph.KeyedDexItem<?>> computeDefinitions(DexType type) {
    Builder<Descriptor<?,?>, com.debughelper.tools.r8.graph.KeyedDexItem<?>> builder = ImmutableMap.builder();
    DexClass clazz = app.definitionFor(type);
    if (clazz != null) {
      clazz.forEachMethod(method -> builder.put(method.getKey(), method));
      clazz.forEachField(field -> builder.put(field.getKey(), field));
    }
    return builder.build();
  }

  public Iterable<DexProgramClass> classes() {
    return app.classes();
  }

  public Iterable<DexProgramClass> classesWithDeterministicOrder() {
    return app.classesWithDeterministicOrder();
  }

  public DexClass definitionFor(DexType type) {
    return app.definitionFor(type);
  }

  public com.debughelper.tools.r8.origin.Origin originFor(DexType type) {
    DexClass definition = app.definitionFor(type);
    return definition == null ? Origin.unknown() : definition.origin;
  }

  public com.debughelper.tools.r8.graph.DexEncodedMethod definitionFor(DexMethod method) {
    return (com.debughelper.tools.r8.graph.DexEncodedMethod) getDefinitions(method.getHolder()).get(method);
  }

  public com.debughelper.tools.r8.graph.DexEncodedField definitionFor(DexField field) {
    return (com.debughelper.tools.r8.graph.DexEncodedField) getDefinitions(field.getHolder()).get(field);
  }

  private Map<Descriptor<?,?>, com.debughelper.tools.r8.graph.KeyedDexItem<?>> getDefinitions(DexType type) {
    Map<Descriptor<?,?>, com.debughelper.tools.r8.graph.KeyedDexItem<?>> typeDefinitions = definitions.get(type);
    if (typeDefinitions != null) {
      return typeDefinitions;
    }

    typeDefinitions = computeDefinitions(type);
    Map<Descriptor<?,?>, KeyedDexItem<?>> existing = definitions.putIfAbsent(type, typeDefinitions);
    return existing != null ? existing : typeDefinitions;
  }

  /**
   * Lookup static method following the super chain from the holder of {@code method}.
   * <p>
   * This method will resolve the method on the holder of {@code method} and only return a non-null
   * value if the result of resolution was a static, non-abstract method.
   *
   * @param method the method to lookup
   * @return The actual target for {@code method} or {@code null} if none found.
   */
  public com.debughelper.tools.r8.graph.DexEncodedMethod lookupStaticTarget(DexMethod method) {
    ResolutionResult resolutionResult = resolveMethod(method.holder, method);
    com.debughelper.tools.r8.graph.DexEncodedMethod target = resolutionResult.asSingleTarget();
    return target == null || target.isStaticMethod() ? target : null;
  }

  /**
   * Lookup super method following the super chain from the holder of {@code method}.
   * <p>
   * This method will resolve the method on the holder of {@code method} and only return a non-null
   * value if the result of resolution was an instance (i.e. non-static) method.
   *
   * @param method the method to lookup
   * @param invocationContext the class the invoke is contained in, i.e., the holder of the caller.
   * @return The actual target for {@code method} or {@code null} if none found.
   */
  public com.debughelper.tools.r8.graph.DexEncodedMethod lookupSuperTarget(DexMethod method,
                                                                       DexType invocationContext) {
    // Make sure we are not chasing NotFoundError.
    ResolutionResult resolutionResult = resolveMethod(method.holder, method);
    if (resolutionResult.asListOfTargets().isEmpty()) {
      return null;
    }
    // Then, resume on the search, but this time, starting from the holder of the caller.
    DexClass contextClass = definitionFor(invocationContext);
    if (contextClass == null || contextClass.superType == null) {
      return null;
    }
    resolutionResult = resolveMethod(contextClass.superType, method);
    com.debughelper.tools.r8.graph.DexEncodedMethod target = resolutionResult.asSingleTarget();
    return target == null || !target.isStaticMethod() ? target : null;
  }

  /**
   * Lookup direct method following the super chain from the holder of {@code method}.
   * <p>
   * This method will lookup private and constructor methods.
   *
   * @param method the method to lookup
   * @return The actual target for {@code method} or {@code null} if none found.
   */
  public com.debughelper.tools.r8.graph.DexEncodedMethod lookupDirectTarget(DexMethod method) {
    ResolutionResult resolutionResult = resolveMethod(method.holder, method);
    com.debughelper.tools.r8.graph.DexEncodedMethod target = resolutionResult.asSingleTarget();
    return target == null || target.isDirectMethod() ? target : null;
  }

  /**
   * Lookup virtual method starting in type and following the super chain.
   * <p>
   * This method will resolve the method on the holder of {@code method} and only return a
   * non-null value if the result of resolution was a non-static, non-private method.
   */
  public com.debughelper.tools.r8.graph.DexEncodedMethod lookupVirtualTarget(DexType type, DexMethod method) {
    assert type.isClassType();
    ResolutionResult resolutionResult = resolveMethodOnClass(type, method);
    com.debughelper.tools.r8.graph.DexEncodedMethod target = resolutionResult.asSingleTarget();
    return target == null || target.isVirtualMethod() ? target : null;
  }

  /**
   * Implements resolution of a method descriptor against a target type.
   * <p>
   * This method will query the definition of the holder to decide on which resolution to use. If
   * the holder is an interface, it delegates to {@link #resolveMethodOnInterface(DexType,
   * DexMethod)}, otherwise {@link #resolveMethodOnClass(DexType, DexMethod)} is used.
   * <p>
   * This is to overcome the shortcoming of the DEX file format that does not allow to encode the
   * kind of a method reference.
   */
  public ResolutionResult resolveMethod(DexType holder, DexMethod method) {
    DexClass definition = definitionFor(holder);
    if (definition == null) {
      return EmptyResult.get();
    }
    return definition.isInterface()
        ? resolveMethodOnInterface(holder, method)
        : resolveMethodOnClass(holder, method);
  }

  /**
   * Implements resolution of a method descriptor against a class type.
   * <p>
   * See <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.3">
   * Section 5.4.3.3 of the JVM Spec</a>.
   * <p>
   * The resolved method is not the method that will actually be invoked. Which methods gets
   * invoked depends on the invoke instruction used. However, it is always safe to rewrite
   * any invoke on the given descriptor to a corresponding invoke on the resolved descriptor, as the
   * resolved method is used as basis for dispatch.
   */
  public ResolutionResult resolveMethodOnClass(DexType holder, DexMethod method) {
    DexClass clazz = definitionFor(holder);
    // Step 1: If holder is an interface, resolution fails with an ICCE. We return null.
    if (clazz == null || clazz.isInterface()) {
      return EmptyResult.get();
    }
    // Step 2:
    com.debughelper.tools.r8.graph.DexEncodedMethod singleTarget = resolveMethodOnClassStep2(clazz, method);
    if (singleTarget != null) {
      return singleTarget;
    }
    // Finally Step 3:
    return resolveMethodStep3(clazz, method);
  }

  /**
   * Implements step 2 of method resolution on classes as per
   * <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.3">
   * Section 5.4.3.3 of the JVM Spec</a>.
   */
  private com.debughelper.tools.r8.graph.DexEncodedMethod resolveMethodOnClassStep2(DexClass clazz, DexMethod method) {
    // Pt. 1: Signature polymorphic method check. Those are only allowed on
    //        java.lang.invoke.MethodHandle, so we only need to look for it if we are looking at
    //        that type.
    // See also <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-2.html#jvms-2.9">
    // Section 2.9 of the JVM Spec</a>.
    if (clazz.type == dexItemFactory.methodHandleType) {
      DexMethod signaturePolymorphic = dexItemFactory.createMethod(clazz.type,
          dexItemFactory.createProto(
              dexItemFactory.objectType, dexItemFactory.objectArrayType),
          method.name);
      com.debughelper.tools.r8.graph.DexEncodedMethod result = clazz.lookupMethod(signaturePolymorphic);
      // Check we found a result and that it has the required access flags for signature polymorphic
      // functions.
      if (result != null && result.accessFlags.isNative() && result.accessFlags.isVarargs()) {
        return result;
      }
    }
    // Pt 2: Find a method that matches the descriptor.
    com.debughelper.tools.r8.graph.DexEncodedMethod result = clazz.lookupMethod(method);
    if (result != null) {
      return result;
    }
    // Pt 3: Apply step two to direct superclass of holder.
    if (clazz.superType != null) {
      DexClass superClass = definitionFor(clazz.superType);
      if (superClass != null) {
        return resolveMethodOnClassStep2(superClass, method);
      }
    }
    return null;
  }

  /**
   * Implements step 3 of
   * <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.3">
   * Section 5.4.3.3 of the JVM Spec</a>. As this is the same for interfaces and classes, we share
   * one implementation.
   * <p>
   * This method will return all maximally specific default methods if there is more than one. If
   * there is no default method, any of the found methods is returned.
   */
  private ResolutionResult resolveMethodStep3(DexClass clazz, DexMethod method) {
    MultiResultBuilder builder = new MultiResultBuilder();
    com.debughelper.tools.r8.graph.DexEncodedMethod anyTarget = resolveMethodStep3Helper(clazz, method, builder);
    ResolutionResult result = builder.build();
    if (result != null) {
      // We have found default methods, return them.
      return result;
    }
    // Return any of the non-default methods.
    return anyTarget == null ? EmptyResult.get() : anyTarget;
  }

  /**
   * Helper method that performs the actual search and adds all maximally specific default methods
   * to the builder. Additionally, one of the maximally specific default methods or, if none exist,
   * any of the found methods, is returned.
   */
  private com.debughelper.tools.r8.graph.DexEncodedMethod resolveMethodStep3Helper(DexClass clazz, DexMethod method,
                                                                               MultiResultBuilder builder) {
    // We are looking for the maximally-specific superinterfaces that have a
    // non-abstract method or any of the abstract methods.
    com.debughelper.tools.r8.graph.DexEncodedMethod result = null;
    for (DexType iface : clazz.interfaces.values) {
      DexClass definiton = definitionFor(iface);
      if (definiton == null) {
        // Ignore missing interface definitions.
        continue;
      }
      com.debughelper.tools.r8.graph.DexEncodedMethod localResult = definiton.lookupMethod(method);
      // Remember the result, if any, as local result.
      result = selectCandidate(result, localResult);
      if (localResult != null && localResult.isNonAbstractVirtualMethod()) {
        // We have found a default method in this class. Remember it and stop the search.
        builder.add(localResult);
      } else {
        // Look at the super-interfaces of this class and keep searching.
        localResult = resolveMethodStep3Helper(definiton, method, builder);
        result = selectCandidate(result, localResult);
      }
    }
    // Now look at indirect super interfaces.
    if (clazz.superType != null) {
      DexClass superClass = definitionFor(clazz.superType);
      if (superClass != null) {
        com.debughelper.tools.r8.graph.DexEncodedMethod superResult = resolveMethodStep3Helper(superClass, method, builder);
        result = selectCandidate(result, superResult);
      }
    }
    return result;
  }

  /**
   * Implements resolution of a method descriptor against an interface type.
   * <p>
   * See <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.3">
   * Section 5.4.3.4 of the JVM Spec</a>.
   * <p>
   * The resolved method is not the method that will actually be invoked. Which methods gets
   * invoked depends on the invoke instruction used. However, it is always save to rewrite
   * any invoke on the given descriptor to a corresponding invoke on the resolved descriptor, as the
   * resolved method is used as basis for dispatch.
   */
  public ResolutionResult resolveMethodOnInterface(DexType holder, DexMethod desc) {
    // Step 1: Lookup interface.
    DexClass definition = definitionFor(holder);
    // If the definition is not an interface, resolution fails with an ICCE. We just return the
    // empty result here.
    if (definition == null || !definition.isInterface()) {
      return EmptyResult.get();
    }
    // Step 2: Look for exact method on interface.
    com.debughelper.tools.r8.graph.DexEncodedMethod result = definition.lookupMethod(desc);
    if (result != null) {
      return result;
    }
    // Step 3: Look for matching method on object class.
    DexClass objectClass = definitionFor(dexItemFactory.objectType);
    if (objectClass == null) {
      // TODO(herhut): This should never happen. How do we handle missing classes?
      return EmptyResult.get();
    }
    result = objectClass.lookupMethod(desc);
    if (result != null && result.accessFlags.isPublic() && !result.accessFlags.isAbstract()) {
      return result;
    }
    // Step 3: Look for maximally-specific superinterface methods or any interface definition.
    //         This is the same for classes and interfaces.
    return resolveMethodStep3(definition, desc);
  }

  /**
   * Lookup instance field starting in type and following the interface and super chain.
   * <p>
   * The result is the field that will be hit at runtime, if such field is known. A result
   * of null indicates that the field is either undefined or not an instance field.
   */
  public com.debughelper.tools.r8.graph.DexEncodedField lookupInstanceTarget(DexType type, DexField field) {
    assert type.isClassType();
    com.debughelper.tools.r8.graph.DexEncodedField result = resolveFieldOn(type, field);
    return result == null || result.accessFlags.isStatic() ? null : result;
  }

  /**
   * Lookup static field starting in type and following the interface and super chain.
   * <p>
   * The result is the field that will be hit at runtime, if such field is known. A result
   * of null indicates that the field is either undefined or not a static field.
   */
  public com.debughelper.tools.r8.graph.DexEncodedField lookupStaticTarget(DexType type, DexField field) {
    assert type.isClassType();
    com.debughelper.tools.r8.graph.DexEncodedField result = resolveFieldOn(type, field);
    return result == null || !result.accessFlags.isStatic() ? null : result;
  }

  /**
   * Implements resolution of a field descriptor against a type.
   * <p>
   * See <a href="https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.3.2">
   * Section 5.4.3.2 of the JVM Spec</a>.
   */
  public com.debughelper.tools.r8.graph.DexEncodedField resolveFieldOn(DexType type, DexField desc) {
    DexClass holder = definitionFor(type);
    if (holder == null) {
      return null;
    }
    // Step 1: Class declares the field.
    DexEncodedField result = holder.lookupField(desc);
    if (result != null) {
      return result;
    }
    // Step 2: Apply recursively to direct superinterfaces. First match succeeds.
    for (DexType iface : holder.interfaces.values) {
      result = resolveFieldOn(iface, desc);
      if (result != null) {
        return result;
      }
    }
    // Step 3: Apply recursively to superclass.
    if (holder.superType != null) {
      result = resolveFieldOn(holder.superType, desc);
      if (result != null) {
        return result;
      }
    }
    return null;
  }

  /**
   * Implements the dispatch logic for a static invoke operation.
   * <p>
   * The only requirement is that the method is indeed static.
   */
  public com.debughelper.tools.r8.graph.DexEncodedMethod dispatchStaticInvoke(ResolutionResult resolvedMethod) {
    com.debughelper.tools.r8.graph.DexEncodedMethod target = resolvedMethod.asSingleTarget();
    if (target != null && target.accessFlags.isStatic()) {
      return target;
    }
    return null;
  }

  /**
   * Implements the dispatch logic for the direct parts of a invokespecial instruction.
   * <p>
   * The only requirement is that the method is not static.
   */
  public com.debughelper.tools.r8.graph.DexEncodedMethod dispatchDirectInvoke(ResolutionResult resolvedMethod) {
    com.debughelper.tools.r8.graph.DexEncodedMethod target = resolvedMethod.asSingleTarget();
    if (target != null && !target.accessFlags.isStatic()) {
      return target;
    }
    return null;
  }

  /**
   * If previous is non-null, selects previous. If current is non-null and a non-private,
   * non-static method, current is selected. Otherwise null is returned.
   */
  private com.debughelper.tools.r8.graph.DexEncodedMethod selectCandidate(com.debughelper.tools.r8.graph.DexEncodedMethod previous, com.debughelper.tools.r8.graph.DexEncodedMethod current) {
    if (previous != null) {
      assert !previous.accessFlags.isPrivate();
      assert !previous.accessFlags.isStatic();
      return previous;
    }
    if (current != null && !current.accessFlags.isPrivate() && !current.accessFlags.isStatic()) {
      return current;
    }
    return null;
  }

  public boolean hasSubtyping() {
    return false;
  }

  public AppInfoWithSubtyping withSubtyping() {
    return null;
  }

  public boolean hasLiveness() {
    return false;
  }

  public Enqueuer.AppInfoWithLiveness withLiveness() {
    return null;
  }

  public void registerNewType(DexType newType, DexType superType) {
    // We do not track subtyping relationships in the basic AppInfo. So do nothing.
  }

  public boolean isInMainDexList(DexType type) {
    return app.mainDexList.contains(type);
  }

  public List<DexClass> getSuperTypeClasses(DexType type) {
    List<DexClass> result = new ArrayList<>();
    do {
      DexClass clazz = definitionFor(type);
      if (clazz == null) {
        break;
      }
      result.add(clazz);
      type = clazz.superType;
    } while (type != null);
    return result;
  }

  public boolean canTriggerStaticInitializer(DexType type) {
    Set<DexType> knownInterfaces = Sets.newIdentityHashSet();

    // Process superclass chain.
    DexType clazz = type;
    while (clazz != null && clazz != dexItemFactory.objectType) {
      DexClass definition = definitionFor(clazz);
      if (canTriggerStaticInitializer(definition)) {
        return true; // Assume it *may* trigger if we didn't find the definition.
      }
      knownInterfaces.addAll(Arrays.asList(definition.interfaces.values));
      clazz = definition.superType;
    }

    // Process interfaces.
    Queue<DexType> queue = new ArrayDeque<>(knownInterfaces);
    while (!queue.isEmpty()) {
      DexType iface = queue.remove();
      DexClass definition = definitionFor(iface);
      if (canTriggerStaticInitializer(definition)) {
        return true; // Assume it *may* trigger if we didn't find the definition.
      }
      if (!definition.isInterface()) {
        throw new Unreachable(iface.toSourceString() + " is expected to be an interface");
      }

      for (DexType superIface : definition.interfaces.values) {
        if (knownInterfaces.add(superIface)) {
          queue.add(superIface);
        }
      }
    }
    return false;
  }

  private static boolean canTriggerStaticInitializer(DexClass clazz) {
    return clazz == null || clazz.hasClassInitializer();
  }

  public interface ResolutionResult {

    com.debughelper.tools.r8.graph.DexEncodedMethod asResultOfResolve();

    com.debughelper.tools.r8.graph.DexEncodedMethod asSingleTarget();

    boolean hasSingleTarget();

    List<com.debughelper.tools.r8.graph.DexEncodedMethod> asListOfTargets();

    void forEachTarget(Consumer<com.debughelper.tools.r8.graph.DexEncodedMethod> consumer);
  }

  private static class MultiResultBuilder {

    private ImmutableList.Builder<com.debughelper.tools.r8.graph.DexEncodedMethod> builder;
    private com.debughelper.tools.r8.graph.DexEncodedMethod singleResult;

    void add(com.debughelper.tools.r8.graph.DexEncodedMethod result) {
      if (builder != null) {
        builder.add(result);
      } else if (singleResult != null) {
        builder = ImmutableList.builder();
        builder.add(singleResult, result);
        singleResult = null;
      } else {
        singleResult = result;
      }
    }

    ResolutionResult build() {
      if (builder != null) {
        return new MultiResult(builder.build());
      } else {
        return singleResult;
      }
    }
  }

  private static class MultiResult implements ResolutionResult {

    private final ImmutableList<com.debughelper.tools.r8.graph.DexEncodedMethod> methods;

    private MultiResult(ImmutableList<com.debughelper.tools.r8.graph.DexEncodedMethod> results) {
      assert results.size() > 1;
      this.methods = results;
    }

    @Override
    public com.debughelper.tools.r8.graph.DexEncodedMethod asResultOfResolve() {
      // Resolution may return any of the targets that were found.
      return methods.get(0);
    }

    @Override
    public com.debughelper.tools.r8.graph.DexEncodedMethod asSingleTarget() {
      // There is no single target that is guaranteed to be called.
      return null;
    }

    @Override
    public boolean hasSingleTarget() {
      return false;
    }

    @Override
    public List<com.debughelper.tools.r8.graph.DexEncodedMethod> asListOfTargets() {
      return methods;
    }

    @Override
    public void forEachTarget(Consumer<com.debughelper.tools.r8.graph.DexEncodedMethod> consumer) {
      methods.forEach(consumer);
    }

  }

  private static class EmptyResult implements ResolutionResult {

    private static final EmptyResult SINGLETON = new EmptyResult();

    private EmptyResult() {
      // Intentionally left empty.
    }

    private static EmptyResult get() {
      return SINGLETON;
    }

    @Override
    public com.debughelper.tools.r8.graph.DexEncodedMethod asResultOfResolve() {
      return null;
    }

    @Override
    public com.debughelper.tools.r8.graph.DexEncodedMethod asSingleTarget() {
      return null;
    }

    @Override
    public boolean hasSingleTarget() {
      return false;
    }

    @Override
    public List<com.debughelper.tools.r8.graph.DexEncodedMethod> asListOfTargets() {
      return Collections.emptyList();
    }

    @Override
    public void forEachTarget(Consumer<DexEncodedMethod> consumer) {
      // Intentionally left empty.
    }

  }
}
