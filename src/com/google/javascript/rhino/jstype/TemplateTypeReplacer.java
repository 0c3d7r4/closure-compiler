/*
 *
 * ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is Rhino code, released
 * May 6, 1999.
 *
 * The Initial Developer of the Original Code is
 * Netscape Communications Corporation.
 * Portions created by the Initial Developer are Copyright (C) 1997-1999
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 *   John Lenz
 *   Google Inc.
 *
 * Alternatively, the contents of this file may be used under the terms of
 * the GNU General Public License Version 2 or later (the "GPL"), in which
 * case the provisions of the GPL are applicable instead of those above. If
 * you wish to allow use of your version of this file only under the terms of
 * the GPL and not to allow others to use your version of this file under the
 * MPL, indicate your decision by deleting the provisions above and replacing
 * them with the notice and other provisions required by the GPL. If you do
 * not delete the provisions above, a recipient may use your version of this
 * file under either the MPL or the GPL.
 *
 * ***** END LICENSE BLOCK ***** */


package com.google.javascript.rhino.jstype;

import static com.google.javascript.jscomp.base.JSCompObjects.identical;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.javascript.jscomp.base.LinkedIdentityHashSet;
import com.google.javascript.rhino.Node;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Specializes {@link TemplatizedType}s according to provided bindings.
 *
 * @author johnlenz@google.com (John Lenz)
 */
public final class TemplateTypeReplacer implements Visitor<JSType> {

  private final JSTypeRegistry registry;
  private final TemplateTypeMap bindings;

  private final boolean visitProperties;
  // TODO(nickreid): We should only need `useUnknownForMissingBinding`. Keeping two separate bits
  // was a quick fix for collapsing two different classes.
  private final boolean useUnknownForMissingKeys;
  private final boolean useUnknownForMissingValues;

  private boolean hasMadeReplacement = false;
  private TemplateType keyType;

  // initialized to null because it's unused in ~40% of TemplateTypeReplacers
  private @Nullable LinkedIdentityHashSet<JSType> seenTypes = null;

  /** Creates a replacer for use during {@code TypeInference}. */
  public static TemplateTypeReplacer forInference(
      JSTypeRegistry registry, Map<TemplateType, JSType> bindings) {
    ImmutableList<TemplateType> keys = ImmutableList.copyOf(bindings.keySet());
    ImmutableList.Builder<JSType> values = ImmutableList.builder();
    for (TemplateType key : keys) {
      JSType value = bindings.get(key);
      values.add(value != null ? value : registry.getNativeType(JSTypeNative.UNKNOWN_TYPE));
    }
    TemplateTypeMap map =
        registry.getEmptyTemplateTypeMap().copyWithExtension(keys, values.build());
    return new TemplateTypeReplacer(registry, map, true, true, true);
  }

  public static TemplateTypeReplacer forInference(
      JSTypeRegistry registry, TemplateTypeMap bindings) {
    return new TemplateTypeReplacer(registry, bindings, true, true, true);
  }
  public static TemplateTypeReplacer forPartialInference(
      JSTypeRegistry registry, TemplateTypeMap bindings) {
    return new TemplateTypeReplacer(registry, bindings, true, false, false);
  }

  /**
   * Creates a replacer that will always totally eliminate {@link TemplateType}s from the
   * definitions of the types it performs replacement on.
   *
   * <p>If a binding for a {@link TemplateType} is required but not provided, `?` will be used.
   */
  public static TemplateTypeReplacer forTotalReplacement(
      JSTypeRegistry registry, TemplateTypeMap bindings) {
    return new TemplateTypeReplacer(registry, bindings, false, false, true);
  }

  /**
   * Creates a replacer that may not totally eliminate {@link TemplateType}s from the definitions of
   * the types it performs replacement on.
   *
   * <p>If a binding for a {@link TemplateType} is required but not provided, uses of that type will
   * not be replaced.
   */
  public static TemplateTypeReplacer forPartialReplacement(
      JSTypeRegistry registry, TemplateTypeMap bindings) {
    return new TemplateTypeReplacer(registry, bindings, false, false, false);
  }

  private TemplateTypeReplacer(
      JSTypeRegistry registry,
      TemplateTypeMap bindings,
      boolean visitProperties,
      boolean useUnknownForMissingKeys,
      boolean useUnknownForMissingValues) {
    this.registry = registry;
    this.bindings = bindings;
    this.visitProperties = visitProperties;
    this.useUnknownForMissingKeys = useUnknownForMissingKeys;
    this.useUnknownForMissingValues = useUnknownForMissingValues;
  }

  public boolean hasMadeReplacement() {
    return this.hasMadeReplacement;
  }

  private void initSeenTypes() {
    if (this.seenTypes == null) {
      this.seenTypes = new LinkedIdentityHashSet<>();
    }
  }

  @Override
  public JSType caseNoType(NoType type) {
    return type;
  }

  @Override
  public JSType caseEnumElementType(EnumElementType type) {
    return type;
  }

  @Override
  public JSType caseAllType() {
    return getNativeType(JSTypeNative.ALL_TYPE);
  }

  @Override
  public JSType caseBooleanType() {
    return getNativeType(JSTypeNative.BOOLEAN_TYPE);
  }

  @Override
  public JSType caseNoObjectType() {
    return getNativeType(JSTypeNative.NO_OBJECT_TYPE);
  }

  @Override
  public JSType caseFunctionType(FunctionType type) {
    return guardAgainstCycles(type, this::caseFunctionTypeUnguarded);
  }

  private JSType caseFunctionTypeUnguarded(FunctionType type) {
    if (isNativeFunctionType(type)) {
      return type;
    }

    if (!type.isOrdinaryFunction() && !type.isConstructor()) {
      return type;
    }

    boolean changed = false;

    JSType beforeThis = type.getTypeOfThis();
    JSType afterThis = coerseToThisType(beforeThis.visit(this));
    if (!identical(beforeThis, afterThis)) {
      changed = true;
    }

    JSType beforeReturn = type.getReturnType();
    JSType afterReturn = beforeReturn.visit(this);
    if (!identical(beforeReturn, afterReturn)) {
      changed = true;
    }

    boolean paramsChanged = false;
    int numParams = type.getParameters().size();
    FunctionParamBuilder paramBuilder =
        numParams == 0 ? null : new FunctionParamBuilder(registry, numParams);
    for (int i = 0; i < numParams; i++) {
      FunctionType.Parameter parameter = type.getParameters().get(i);
      JSType beforeParamType = parameter.getJSType();
      JSType afterParamType = beforeParamType.visit(this);

      if (!identical(beforeParamType, afterParamType)) {
        changed = true;
        paramsChanged = true;
        // TODO(lharker): we could also lazily create the FunctionParamBuilder here, but that would
        // require re-iterating over all previously seen params so unclear it's worth the complexity
        if (parameter.isOptional()) {
          paramBuilder.addOptionalParams(afterParamType);
        } else if (parameter.isVariadic()) {
          paramBuilder.addVarArgs(afterParamType);
        } else {
          paramBuilder.addRequiredParams(afterParamType);
        }
      } else {
        paramBuilder.newParameterFrom(parameter);
      }
    }

    if (changed) {
      return type.toBuilder()
          .withParameters(paramsChanged ? paramBuilder.build() : type.getParameters())
          .withReturnType(afterReturn)
          .withTypeOfThis(afterThis)
          .withIsAbstract(false) // TODO(b/187989034): Copy this from the source function.
          .build();
    }

    return type;
  }

  private JSType coerseToThisType(JSType type) {
    return type != null ? type : registry.getNativeObjectType(JSTypeNative.UNKNOWN_TYPE);
  }

  @Override
  public JSType caseObjectType(ObjectType objType) {
    return guardAgainstCycles(objType, this::caseObjectTypeUnguarded);
  }

  private JSType caseObjectTypeUnguarded(ObjectType objType) {
    if (!visitProperties
        || objType.isNominalType()
        || objType instanceof ProxyObjectType
        || !objType.isRecordType()) {
      return objType;
    }

    boolean changed = false;
    RecordTypeBuilder builder = new RecordTypeBuilder(registry);
    for (String prop : objType.getOwnPropertyNames()) {
      Node propertyNode = objType.getPropertyNode(prop);
      JSType beforeType = objType.getPropertyType(prop);
      JSType afterType = beforeType.visit(this);
      if (!identical(beforeType, afterType)) {
        changed = true;
      }
      builder.addProperty(prop, afterType, propertyNode);
    }

    if (changed) {
      return builder.build();
    }

    return objType;
  }

  @Override
  public JSType caseTemplatizedType(TemplatizedType type) {
    return guardAgainstCycles(type, this::caseTemplatizedTypeUnguarded);
  }

  private JSType caseTemplatizedTypeUnguarded(TemplatizedType type) {
    boolean changed = false;
    ObjectType beforeBaseType = type.getReferencedType();
    ObjectType afterBaseType = ObjectType.cast(beforeBaseType.visit(this));
    if (!identical(beforeBaseType, afterBaseType)) {
      changed = true;
    }

    ImmutableList.Builder<JSType> builder = ImmutableList.builder();
    for (JSType beforeTemplateType : type.getTemplateTypes()) {
      if(beforeTemplateType instanceof NamedType && beforeTemplateType.isResolved()) {
        beforeTemplateType = ((NamedType) beforeTemplateType).getReferencedType();
      }
      JSType afterTemplateType = beforeTemplateType.visit(this);
      if (!identical(beforeTemplateType, afterTemplateType)) {
        changed = true;
      }
      builder.add(afterTemplateType);
    }

    HashMap<String, TemplateType> afterOwnTemplateTypes = type.getOwnTemplateTypes();
    if(afterOwnTemplateTypes != null) {
      var replaced = replaceOwnTemplateTypes(afterOwnTemplateTypes, type);
      if(replaced != null) {
        afterOwnTemplateTypes = replaced;
        changed = true;
      }
    }

    if (changed) {
      type = registry.createTemplatizedType(afterBaseType, builder.build(), afterOwnTemplateTypes);
    }
    return type;
  }

  /**
   * Updates the own template types (added with `@typedef` notation in root JSDoc) of templatized/union types.
   */
  private HashMap<String, TemplateType> replaceOwnTemplateTypes(HashMap<String, TemplateType> ownTemplateTypes, JSType type) {
    boolean changed = false;
    HashMap<String, TemplateType> afterOwnTemplateTypes = new HashMap<>();

    for (String ownTemplateTypeKey : ownTemplateTypes.keySet()) {
      var beforeOwnTemplateType = ownTemplateTypes.get(ownTemplateTypeKey);
      var afterOwnTemplateType = beforeOwnTemplateType.visit(this);
      if (!identical(beforeOwnTemplateType, afterOwnTemplateType)) {
        changed = true;
      }
      if(afterOwnTemplateType.toMaybeTemplateType() == null) {
         afterOwnTemplateType=beforeOwnTemplateType;
      }
      afterOwnTemplateTypes.put(ownTemplateTypeKey, (TemplateType) afterOwnTemplateType);
    }

    if(!changed) return null;
    return afterOwnTemplateTypes;
  }

  @Override
  public JSType caseUnknownType() {
    return getNativeType(JSTypeNative.UNKNOWN_TYPE);
  }

  @Override
  public JSType caseNullType() {
    return getNativeType(JSTypeNative.NULL_TYPE);
  }

  @Override
  public JSType caseNumberType() {
    return getNativeType(JSTypeNative.NUMBER_TYPE);
  }

  @Override
  public JSType caseBigIntType() {
    return getNativeType(JSTypeNative.BIGINT_TYPE);
  }

  @Override
  public JSType caseStringType() {
    return getNativeType(JSTypeNative.STRING_TYPE);
  }

  @Override
  public JSType caseSymbolType() {
    return getNativeType(JSTypeNative.SYMBOL_TYPE);
  }

  @Override
  public JSType caseVoidType() {
    return getNativeType(JSTypeNative.VOID_TYPE);
  }

  @Override
  public JSType caseUnionType(UnionType type) {
    return guardAgainstCycles(type, this::caseUnionTypeUnguarded);
  }

  private JSType caseUnionTypeUnguarded(UnionType type) {
    boolean changed = false;
    List<JSType> results = new ArrayList<>();
    ImmutableList<JSType> alternates = type.getAlternates();
    int alternateCount = alternates.size();
    for (int i = 0; i < alternateCount; i++) {
      var alternative = alternates.get(i);
      JSType replacement = alternative.visit(this);
      if (!identical(replacement, alternative)) {
        changed = true;
      }
      results.add(replacement);
    }

    HashMap<String, TemplateType> afterOwnTemplateTypes = type.getOwnTemplateTypes();
    if(afterOwnTemplateTypes != null) {
      var replaced = replaceOwnTemplateTypes(afterOwnTemplateTypes, type);
      if(replaced != null) {
        afterOwnTemplateTypes = replaced;
        changed = true;
      }
    }

    if (changed) {
      return registry.createUnionType(results, afterOwnTemplateTypes); // maybe not a union
    }

    return type;
  }

  @Override
  public JSType caseTemplateType(TemplateType type) {
    this.hasMadeReplacement = true;

    if (!bindings.hasTemplateKey(type)) {
      return useUnknownForMissingKeys ? getNativeType(JSTypeNative.UNKNOWN_TYPE) : type;
    }

    this.initSeenTypes();
    if (seenTypes.contains(type)) {
      // If we have already encountered this TemplateType during replacement
      // (i.e. there is a reference loop) then return the TemplateType type itself.
      return type;
    } else if (!bindings.hasTemplateType(type)) {
      // If there is no JSType substitution for the TemplateType, return either the
      // UNKNOWN_TYPE or the TemplateType type itself, depending on configuration.
      return useUnknownForMissingValues ? getNativeType(JSTypeNative.UNKNOWN_TYPE) : type;
    } else {
      JSType replacement = bindings.getUnresolvedOriginalTemplateType(type);
      if (replacement == keyType || isRecursive(type, replacement)) {
        // Recursive templated type definition (e.g. T resolved to Foo<T>).
        return type;
      }

      seenTypes.add(type);
      JSType visitedReplacement = replacement.visit(this);
      seenTypes.remove(type);

      Preconditions.checkState(
          !identical(visitedReplacement, keyType),
          "Trying to replace key %s with the same value",
          keyType);
      return visitedReplacement;
    }
  }

  private JSType getNativeType(JSTypeNative nativeType) {
    return registry.getNativeType(nativeType);
  }

  private boolean isNativeFunctionType(FunctionType type) {
    return type.isNativeObjectType();
  }

  public JSType caseNamedTypeRefUnguarded(JSType ref) {
    return ref.visit(this);
  }
  
  @Override
  public JSType caseNamedType(NamedType type) {
    if(!type.isResolved() || type.getReferencedType() == null) {
      // The internals of a named type aren't interesting.
      return type;
    }
    var ref=type.getReferencedType();
    return guardAgainstCycles(ref, this::caseNamedTypeRefUnguarded);
  }

  @Override
  public JSType caseProxyObjectType(ProxyObjectType type) {
    return guardAgainstCycles(type, this::caseProxyObjectTypeUnguarded);
  }

  private JSType caseProxyObjectTypeUnguarded(ProxyObjectType type) {
    // Be careful not to unwrap a type unless it has changed.
    JSType beforeType = type.getReferencedTypeInternal();
    JSType replacement = beforeType.visit(this);
    if (!identical(replacement, beforeType)) {
      return replacement;
    }
    return type;
  }

  void setKeyType(TemplateType keyType) {
    this.keyType = keyType;
  }

  /**
   * Returns whether the replacement type is a templatized type which contains the current type.
   * e.g. current type T is being replaced with Foo<T>
   */
  private boolean isRecursive(TemplateType currentType, JSType replacementType) {
    // Avoid calling "restrictBy..." here as this method ends up being very hot and
    // rebuilding unions is expensive.

    TemplatizedType replacementTemplatizedType = null;
    if (replacementType.isUnionType()) {
      UnionType union = replacementType.toMaybeUnionType();
      ImmutableList<JSType> alternates = union.getAlternates();
      int alternatesCount = alternates.size();

      for (int i = 0; i < alternatesCount; i++) {
        JSType t = alternates.get(i);
        if (t.isNullType() || t.isVoidType()) {
          continue;
        }
        if (t.isTemplatizedType()) {
          if (replacementTemplatizedType != null) {
            // TODO(johnlenz): seems like we should check a union of templatized types for
            // recursion but this is the existing behavior.
            return false;
          } else {
            replacementTemplatizedType = t.toMaybeTemplatizedType();
          }
        } else {
          // The union contains a untemplatized type.
          return false;
        }
      }
    } else {
      replacementTemplatizedType = replacementType.toMaybeTemplatizedType();
    }

    if (replacementTemplatizedType == null) {
      return false;
    }

    ImmutableList<JSType> replacementTemplateTypes = replacementTemplatizedType.getTemplateTypes();
    int replacementCount = replacementTemplateTypes.size();
    for (int i = 0; i < replacementCount; i++) {
      JSType replacementTemplateType = replacementTemplateTypes.get(i);
      if (replacementTemplateType.isTemplateType()
          && isSameType(currentType, replacementTemplateType.toMaybeTemplateType())) {
        return true;
      }
    }

    return false;
  }

  private boolean isSameType(TemplateType currentType, TemplateType replacementType) {
    return identical(currentType, replacementType)
        || identical(currentType, bindings.getUnresolvedOriginalTemplateType(replacementType));
  }

  private <T extends JSType> JSType guardAgainstCycles(T type, Function<T, JSType> mapper) {
    this.initSeenTypes();
    if (!this.seenTypes.add(type)) {
      return type;
    }
    try {
      return mapper.apply(type);
    } finally {
      this.seenTypes.remove(type);
    }
  }
}
