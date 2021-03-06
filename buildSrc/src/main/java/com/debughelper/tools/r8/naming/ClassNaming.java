// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.debughelper.tools.r8.naming;

import com.debughelper.tools.r8.naming.MemberNaming.Signature;
import com.debughelper.tools.r8.utils.ThrowingConsumer;

/**
 * Stores name information for a class.
 * <p>
 * Implementers will include how the class was renamed and information on the class's members.
 */
public interface ClassNaming {

  abstract class Builder {
    public abstract Builder addMemberEntry(MemberNaming entry);

    public abstract ClassNaming build();

    /** This is an optional method, may be implemented as no-op */
    public abstract void addMappedRange(
        Range obfuscatedRange,
        MemberNaming.MethodSignature originalSignature,
        Object originalRange,
        String obfuscatedName);
  }

  MemberNaming lookup(MemberNaming.Signature renamedSignature);

  MemberNaming lookupByOriginalSignature(MemberNaming.Signature original);

  <T extends Throwable> void forAllMemberNaming(
      ThrowingConsumer<MemberNaming, T> consumer) throws T;

  <T extends Throwable> void forAllFieldNaming(
      ThrowingConsumer<MemberNaming, T> consumer) throws T;

  <T extends Throwable> void forAllMethodNaming(
      ThrowingConsumer<MemberNaming, T> consumer) throws T;
}
