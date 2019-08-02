// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.naming;

import com.debughelper.tools.r8.graph.DexField;
import com.debughelper.tools.r8.graph.DexMethod;
import com.debughelper.tools.r8.graph.DexType;
import com.debughelper.tools.r8.naming.ClassNaming;
import com.debughelper.tools.r8.naming.MemberNaming;
import com.debughelper.tools.r8.naming.MemberNaming.FieldSignature;
import com.debughelper.tools.r8.naming.MemberNaming.MethodSignature;
import com.debughelper.tools.r8.naming.MemberNaming.Signature;
import com.debughelper.tools.r8.naming.MemberNaming.Signature.SignatureKind;
import com.debughelper.tools.r8.utils.ThrowingConsumer;
import com.google.common.collect.ImmutableMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Stores name information for a class.
 * <p>
 * The main differences of this against {@link ClassNamingForNameMapper} are:
 *   1) field and method mappings are maintained and searched separately for faster lookup;
 *   2) similar to the relation between {@link ClassNameMapper} and {@link SeedMapper}, this one
 *   uses original {@link Signature} as a key to look up {@link com.debughelper.tools.r8.naming.MemberNaming},
 *   whereas {@link ClassNamingForNameMapper} uses renamed {@link Signature} as a key; and thus
 *   3) logic of {@link #lookup} and {@link #lookupByOriginalSignature} are inverted; and
 *   4) {@link #lookupByOriginalItem}'s are introduced for lightweight lookup.
 */
public class ClassNamingForMapApplier implements com.debughelper.tools.r8.naming.ClassNaming {

  public static class Builder extends com.debughelper.tools.r8.naming.ClassNaming.Builder {
    private final String originalName;
    private final String renamedName;
    private final Map<MethodSignature, com.debughelper.tools.r8.naming.MemberNaming> methodMembers = new HashMap<>();
    private final Map<FieldSignature, com.debughelper.tools.r8.naming.MemberNaming> fieldMembers = new HashMap<>();

    private Builder(String renamedName, String originalName) {
      this.originalName = originalName;
      this.renamedName = renamedName;
    }

    @Override
    public ClassNaming.Builder addMemberEntry(com.debughelper.tools.r8.naming.MemberNaming entry) {
      // Unlike {@link ClassNamingForNameMapper.Builder#addMemberEntry},
      // the key is original signature.
      if (entry.isMethodNaming()) {
        methodMembers.put((MethodSignature) entry.getOriginalSignature(), entry);
      } else {
        fieldMembers.put((FieldSignature) entry.getOriginalSignature(), entry);
      }
      return this;
    }

    @Override
    public ClassNamingForMapApplier build() {
      return new ClassNamingForMapApplier(renamedName, originalName, methodMembers, fieldMembers);
    }

    @Override
    /** No-op */
    public void addMappedRange(
        Range obfuscatedRange,
        MethodSignature originalSignature,
        Object originalRange,
        String obfuscatedName) {}
  }

  static Builder builder(String renamedName, String originalName) {
    return new Builder(renamedName, originalName);
  }

  private final String originalName;
  final String renamedName;

  private final ImmutableMap<MethodSignature, com.debughelper.tools.r8.naming.MemberNaming> methodMembers;
  private final ImmutableMap<FieldSignature, com.debughelper.tools.r8.naming.MemberNaming> fieldMembers;

  // Constructor to help chaining {@link ClassNamingForMapApplier} according to class hierarchy.
  ClassNamingForMapApplier(ClassNamingForMapApplier proxy) {
    this(proxy.renamedName, proxy.originalName, proxy.methodMembers, proxy.fieldMembers);
  }

  private ClassNamingForMapApplier(
      String renamedName,
      String originalName,
      Map<MethodSignature, com.debughelper.tools.r8.naming.MemberNaming> methodMembers,
      Map<FieldSignature, com.debughelper.tools.r8.naming.MemberNaming> fieldMembers) {
    this.renamedName = renamedName;
    this.originalName = originalName;
    this.methodMembers = ImmutableMap.copyOf(methodMembers);
    this.fieldMembers = ImmutableMap.copyOf(fieldMembers);
  }

  @Override
  public <T extends Throwable> void forAllMemberNaming(
      ThrowingConsumer<com.debughelper.tools.r8.naming.MemberNaming, T> consumer) throws T {
    forAllFieldNaming(consumer);
    forAllMethodNaming(consumer);
  }

  @Override
  public <T extends Throwable> void forAllFieldNaming(
      ThrowingConsumer<com.debughelper.tools.r8.naming.MemberNaming, T> consumer) throws T {
    for (com.debughelper.tools.r8.naming.MemberNaming naming : fieldMembers.values()) {
      consumer.accept(naming);
    }
  }

  @Override
  public <T extends Throwable> void forAllMethodNaming(
      ThrowingConsumer<com.debughelper.tools.r8.naming.MemberNaming, T> consumer) throws T {
    for (com.debughelper.tools.r8.naming.MemberNaming naming : methodMembers.values()) {
      consumer.accept(naming);
    }
  }

  @Override
  public com.debughelper.tools.r8.naming.MemberNaming lookup(Signature renamedSignature) {
    // As the key is inverted, this looks a lot like
    //   {@link ClassNamingForNameMapper#lookupByOriginalSignature}.
    if (renamedSignature.kind() == SignatureKind.METHOD) {
      for (com.debughelper.tools.r8.naming.MemberNaming memberNaming : methodMembers.values()) {
        if (memberNaming.getRenamedSignature().equals(renamedSignature)) {
          return memberNaming;
        }
      }
      return null;
    } else {
      assert renamedSignature.kind() == SignatureKind.FIELD;
      for (com.debughelper.tools.r8.naming.MemberNaming memberNaming : fieldMembers.values()) {
        if (memberNaming.getRenamedSignature().equals(renamedSignature)) {
          return memberNaming;
        }
      }
      return null;
    }
  }

  @Override
  public com.debughelper.tools.r8.naming.MemberNaming lookupByOriginalSignature(Signature original) {
    // As the key is inverted, this looks a lot like {@link ClassNamingForNameMapper#lookup}.
    if (original.kind() == SignatureKind.METHOD) {
      return methodMembers.get(original);
    } else {
      assert original.kind() == SignatureKind.FIELD;
      return fieldMembers.get(original);
    }
  }

  com.debughelper.tools.r8.naming.MemberNaming lookupByOriginalItem(DexField field) {
    for (Map.Entry<FieldSignature, com.debughelper.tools.r8.naming.MemberNaming> entry : fieldMembers.entrySet()) {
      FieldSignature signature = entry.getKey();
      if (signature.name.equals(field.name.toString())
          && signature.type.equals(field.type.getName())) {
        return entry.getValue();
      }
    }
    return null;
  }

  protected com.debughelper.tools.r8.naming.MemberNaming lookupByOriginalItem(DexMethod method) {
    for (Map.Entry<MethodSignature, MemberNaming> entry : methodMembers.entrySet()) {
      MethodSignature signature = entry.getKey();
      if (signature.name.equals(method.name.toString())
          && signature.type.equals(method.proto.returnType.toString())
          && Arrays.equals(signature.parameters,
              Arrays.stream(method.proto.parameters.values)
                  .map(DexType::toString).toArray(String[]::new))) {
        return entry.getValue();
      }
    }
    return null;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ClassNamingForMapApplier)) {
      return false;
    }

    ClassNamingForMapApplier that = (ClassNamingForMapApplier) o;

    return originalName.equals(that.originalName)
        && renamedName.equals(that.renamedName)
        && methodMembers.equals(that.methodMembers)
        && fieldMembers.equals(that.fieldMembers);
  }

  @Override
  public int hashCode() {
    int result = originalName.hashCode();
    result = 31 * result + renamedName.hashCode();
    result = 31 * result + methodMembers.hashCode();
    result = 31 * result + fieldMembers.hashCode();
    return result;
  }
}
