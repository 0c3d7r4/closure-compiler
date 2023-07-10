/*
 * Copyright 2014 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkState;
import static com.google.javascript.jscomp.AstFactory.type;

import com.google.common.base.Predicate;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Table;
import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.colors.StandardColors;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.jscomp.parsing.parser.FeatureSet.Feature;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Rewrite "let"s and "const"s as "var"s. Rename block-scoped declarations and their references when
 * necessary.
 *
 * <p>Note that this must run after Es6RewriteDestructuring, since it does not process destructuring
 * let/const declarations at all.
 *
 * <p>TODO(moz): Try to use MakeDeclaredNamesUnique
 */
public final class Es6RewriteBlockScopedDeclaration extends AbstractPostOrderCallback
    implements CompilerPass {

  private final AbstractCompiler compiler;
  private final AstFactory astFactory;
  private final Table<Node, String, String> renameTable = HashBasedTable.create();
  private final Set<Node> letConsts = new LinkedHashSet<>();
  private final Set<String> undeclaredNames = new LinkedHashSet<>();
  private final Set<String> externNames = new LinkedHashSet<>();
  private static final FeatureSet transpiledFeatures =
      FeatureSet.BARE_MINIMUM.with(Feature.LET_DECLARATIONS, Feature.CONST_DECLARATIONS);
  private final UniqueIdSupplier uniqueIdSupplier;
  private final boolean astMayHaveUndeclaredVariables;

  public Es6RewriteBlockScopedDeclaration(AbstractCompiler compiler) {
    this.compiler = compiler;
    this.uniqueIdSupplier = compiler.getUniqueIdSupplier();
    this.astMayHaveUndeclaredVariables = compiler.getOptions().skipNonTranspilationPasses;
    this.astFactory = compiler.createAstFactory();
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    if (!n.hasChildren() || !NodeUtil.isBlockScopedDeclaration(n.getFirstChild())) {
      return;
    }
    // NOTE: This pass depends on for-of being transpiled away before it runs.
    checkState(parent == null || !parent.isForOf(), parent);

    if (n.isLet() || n.isConst()) {
      letConsts.add(n);
    }
    if (NodeUtil.isNameDeclaration(n)) {
      for (Node nameNode = n.getFirstChild(); nameNode != null; nameNode = nameNode.getNext()) {
        visitBlockScopedName(t, n, nameNode);
      }
    } else {
      // NOTE: This pass depends on class declarations having been transpiled away
      checkState(n.isFunction() || n.isCatch(), "Unexpected declaration node: %s", n);
      visitBlockScopedName(t, n, n.getFirstChild());
    }
  }

  @Override
  public void process(Node externs, Node root) {
    if (this.astMayHaveUndeclaredVariables) {
      // If we are only transpiling, we may have undefined variables in the code.
      NodeTraversal.traverse(compiler, root, new CollectUndeclaredNames());
    }
    // Record names declared in externs to prevent collisions when declaring vars from let/const.
    this.externNames.addAll(NodeUtil.collectExternVariableNames(compiler, externs));
    NodeTraversal.traverse(compiler, root, this);
    NodeTraversal.traverse(compiler, root, new Es6RenameReferences(renameTable));
    LoopClosureTransformer transformer = new LoopClosureTransformer();
    NodeTraversal.traverse(compiler, root, transformer);
    transformer.transformLoopClosure();
    rewriteDeclsToVars();
    TranspilationPasses.maybeMarkFeaturesAsTranspiledAway(compiler, transpiledFeatures);
  }

  /**
   * Renames block-scoped declarations that shadow a variable in an outer scope
   *
   * <p>Also normalizes declarations with no initializer in a loop to be initialized to undefined.
   */
  private void visitBlockScopedName(NodeTraversal t, Node decl, Node nameNode) {
    Scope scope = t.getScope();
    Node parent = decl.getParent();
    // Normalize "let x;" to "let x = undefined;" if in a loop, since we later convert x
    // to be $jscomp$loop$0.x and want to reset the property to undefined every loop iteration.
    if ((decl.isLet() || decl.isConst())
        && !nameNode.hasChildren()
        && (parent == null || !parent.isForIn())
        && inLoop(decl)) {
      Node undefined = astFactory.createUndefinedValue().srcrefTree(nameNode);
      nameNode.addChildToFront(undefined);
      compiler.reportChangeToEnclosingScope(undefined);
    }

    String oldName = nameNode.getString();
    Scope hoistScope = scope.getClosestHoistScope();
    if (scope != hoistScope) {
      String newName = oldName;
      if (hoistScope.hasSlot(oldName)
          || undeclaredNames.contains(oldName)
          || externNames.contains(oldName)) {
        do {
          newName = oldName + "$" + uniqueIdSupplier.getUniqueId(t.getInput());
        } while (hoistScope.hasSlot(newName));
        nameNode.setString(newName);
        compiler.reportChangeToEnclosingScope(nameNode);
        Node scopeRoot = scope.getRootNode();
        renameTable.put(scopeRoot, oldName, newName);
      }
      Var oldVar = scope.getVar(oldName);
      scope.undeclare(oldVar);
      hoistScope.declare(newName, nameNode, oldVar.getInput());
    }
  }

  /**
   * Whether n is inside a loop. If n is inside a function which is inside a loop, we do not
   * consider it to be inside a loop.
   */
  private boolean inLoop(Node n) {
    Node enclosingNode = NodeUtil.getEnclosingNode(n, isLoopOrFunction);
    return enclosingNode != null && !enclosingNode.isFunction();
  }

  private static final Predicate<Node> isLoopOrFunction =
      new Predicate<Node>() {
        @Override
        public boolean apply(Node n) {
          return n.isFunction() || NodeUtil.isLoopStructure(n);
        }
      };

  private static void extractInlineJSDoc(Node srcDeclaration, Node srcName, Node destDeclaration) {
    JSDocInfo existingInfo = srcDeclaration.getJSDocInfo();
    if (existingInfo == null) {
      // Extract inline JSDoc from "src" and add it to the "dest" node.
      existingInfo = srcName.getJSDocInfo();
      srcName.setJSDocInfo(null);
    }
    JSDocInfo.Builder builder = JSDocInfo.Builder.maybeCopyFrom(existingInfo);
    destDeclaration.setJSDocInfo(builder.build());
  }

  private static void maybeAddConstJSDoc(Node srcDeclaration, Node srcName, Node destDeclaration) {
    if (srcDeclaration.isConst()) {
      extractInlineJSDoc(srcDeclaration, srcName, destDeclaration);
      JSDocInfo.Builder builder = JSDocInfo.Builder.maybeCopyFrom(destDeclaration.getJSDocInfo());
      builder.recordConstancy();
      destDeclaration.setJSDocInfo(builder.build());
    }
  }

  private void handleDeclarationList(Node declarationList, Node parent) {
    // Normalize: "const i = 0, j = 0;" becomes "/** @const */ var i = 0; /** @const */ var j = 0;"
    while (declarationList.hasMoreThanOneChild()) {
      Node name = declarationList.getLastChild();
      Node newDeclaration = IR.var(name.detach()).srcref(declarationList);
      maybeAddConstJSDoc(declarationList, name, newDeclaration);
      newDeclaration.insertAfter(declarationList);
      compiler.reportChangeToEnclosingScope(parent);
    }
    maybeAddConstJSDoc(declarationList, declarationList.getFirstChild(), declarationList);
    declarationList.setToken(Token.VAR);
  }

  private void addNodeBeforeLoop(Node newNode, Node loopNode) {
    Node insertSpot = loopNode;
    while (insertSpot.getParent().isLabel()) {
      insertSpot = insertSpot.getParent();
    }
    newNode.insertBefore(insertSpot);
    compiler.reportChangeToEnclosingScope(newNode);
  }

  private void rewriteDeclsToVars() {
    if (!letConsts.isEmpty()) {
      for (Node n : letConsts) {
        if (n.isConst()) {
          handleDeclarationList(n, n.getParent());
        }
        n.setToken(Token.VAR);
        compiler.reportChangeToEnclosingScope(n);
      }
    }
  }

  /**
   * Records undeclared names and aggressively rename possible references to them. Eg: In "{ let
   * inner; } use(inner);", we rename the let declared variable.
   */
  private class CollectUndeclaredNames extends AbstractPostOrderCallback {

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isName() && !t.getScope().hasSlot(n.getString())) {
        undeclaredNames.add(n.getString());
      }
    }
  }

  /** Transforms let/const declarations captured by loop closures. */
  private class LoopClosureTransformer extends AbstractPostOrderCallback {

    private static final String LOOP_OBJECT_NAME = "$jscomp$loop";
    private static final String LOOP_OBJECT_PROPERTY_NAME = "$jscomp$loop$prop$";
    private final Map<Node, LoopObject> loopObjectMap = new LinkedHashMap<>();

    private final SetMultimap<Node, LoopObject> nodesRequiringLoopObjectsClosureMap =
        LinkedHashMultimap.create();
    private final SetMultimap<Node, String> nodesHandledForLoopObjectClosure =
        HashMultimap.create();
    private final SetMultimap<Var, Node> referenceMap = LinkedHashMultimap.create();
    // Maps from a var to a unique property name for that var
    // e.g. 'i' -> '$jscomp$loop$prop$i$0'
    private final Map<Var, String> propertyNameMap = new LinkedHashMap<>();

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (!NodeUtil.isReferenceName(n)) {
        return;
      }

      String name = n.getString();
      Scope referencedIn = t.getScope();
      Var var = referencedIn.getVar(name);
      if (var == null) {
        return;
      }

      if (!var.isLet() && !var.isConst()) {
        return;
      }

      if (n.getParent().isLet() || n.getParent().isConst()) {
        letConsts.add(n.getParent());
      }

      // Traverse nodes up from let/const declaration:
      // If we hit a function or the root before a loop - Not a loop closure.
      // if we hit a loop first - maybe loop closure.
      Scope declaredIn = var.getScope();
      Node loopNode = null;
      for (Scope s = declaredIn; ; s = s.getParent()) {
        Node scopeRoot = s.getRootNode();
        if (NodeUtil.isLoopStructure(scopeRoot)) {
          loopNode = scopeRoot;
          break;
        } else if (scopeRoot.hasParent() && NodeUtil.isLoopStructure(scopeRoot.getParent())) {
          loopNode = scopeRoot.getParent();
          break;
        } else if (s.isFunctionBlockScope() || s.isGlobal()) {
          return;
        }
      }

      referenceMap.put(var, n);

      // Traverse scopes from reference scope to declaration scope.
      // If we hit a function - loop closure detected.
      Scope outerMostFunctionScope = null;
      for (Scope s = referencedIn;
          s != declaredIn && s.getRootNode() != loopNode;
          s = s.getParent()) {
        if (s.isFunctionScope()) {
          outerMostFunctionScope = s;
        }
      }

      if (outerMostFunctionScope != null) {
        Node enclosingFunction = outerMostFunctionScope.getRootNode();

        // There are two categories of functions we might find here:
        //  1. a getter or setter in an object literal. We will wrap the entire object literal in
        //     a closure to capture the value of the let/const.
        //  2. a function declaration or expression. We will wrap the function in a closure.
        // (At this point, class methods/getters/setters and object literal member functions are
        // transpiled away.)
        final Node nodeToWrapInClosure;
        if (enclosingFunction.getParent().isGetterDef()
            || enclosingFunction.getParent().isSetterDef()) {
          nodeToWrapInClosure = enclosingFunction.getGrandparent();
          checkState(nodeToWrapInClosure.isObjectLit());
        } else {
          nodeToWrapInClosure = enclosingFunction;
        }
        if (nodesHandledForLoopObjectClosure.containsEntry(nodeToWrapInClosure, name)) {
          return;
        }
        nodesHandledForLoopObjectClosure.put(nodeToWrapInClosure, name);

        LoopObject object =
            loopObjectMap.computeIfAbsent(
                loopNode, (Node k) -> new LoopObject(createUniqueObjectName(t.getInput())));
        String newPropertyName = createUniquePropertyName(var);
        object.vars.add(var);
        propertyNameMap.put(var, newPropertyName);
        nodesRequiringLoopObjectsClosureMap.put(nodeToWrapInClosure, object);
      }
    }

    private String createUniqueObjectName(CompilerInput input) {
      return LOOP_OBJECT_NAME + "$" + uniqueIdSupplier.getUniqueId(input);
    }

    // Normalization guarantees unique variable names so generating a unique ID is necessary
    // because this pass runs after normalization.
    private String createUniquePropertyName(Var var) {
      return LOOP_OBJECT_PROPERTY_NAME
          + var.getName()
          + "$"
          + uniqueIdSupplier.getUniqueId(var.getInput());
    }

    private void transformLoopClosure() {
      if (loopObjectMap.isEmpty()) {
        return;
      }

      for (Node loopNode : loopObjectMap.keySet()) {
        // Introduce objects to reflect the captured scope variables.
        // Fields are initially left as undefined to avoid cases like:
        //   var $jscomp$loop$0 = {$jscomp$loop$prop$i: 0, $jscomp$loop$prop$j: $jscomp$loop$0.i}
        // They are initialized lazily by changing declarations into assignments
        // later.
        LoopObject loopObject = loopObjectMap.get(loopNode);
        Node objectLitNextIteration = astFactory.createObjectLit();
        renameVarsToProperties(loopObject, objectLitNextIteration);

        Node updateLoopObject =
            astFactory.createAssign(createLoopObjectNameNode(loopObject), objectLitNextIteration);
        Node objectLit =
            IR.var(createLoopObjectNameNode(loopObject), astFactory.createObjectLit())
                .srcrefTree(loopNode);
        addNodeBeforeLoop(objectLit, loopNode);
        if (loopNode.isVanillaFor()) { // For
          changeVanillaForLoopHeader(loopNode, updateLoopObject);
        } else {
          final Node loopBody = NodeUtil.getLoopCodeBlock(loopNode);
          loopBody.addChildToFront(IR.exprResult(updateLoopObject).srcrefTreeIfMissing(loopNode));
        }
        compiler.reportChangeToEnclosingScope(loopNode);

        changeDeclarationsToAssignments(loopNode, loopObject);
      }

      // TODO(user): Move the logic of creating the wrapper function to the top of this
      // method to create a unique function name and assign properties with the right loop object
      // name the first time instead of renaming it later
      createWrapperFunctions();
    }

    /** Create wrapper functions and call them. */
    private void createWrapperFunctions() {
      for (Node functionOrObjectLit : nodesRequiringLoopObjectsClosureMap.keySet()) {
        Node returnNode = IR.returnNode();
        Set<LoopObject> objects = nodesRequiringLoopObjectsClosureMap.get(functionOrObjectLit);
        Node[] objectNames = new Node[objects.size()];
        Node[] objectNamesForCall = new Node[objects.size()];
        int i = 0;
        for (LoopObject object : objects) {
          objectNames[i] = createLoopObjectNameNode(object);
          objectNamesForCall[i] = createLoopObjectNameNode(object);
          i++;
        }

        Node iife =
            astFactory.createFunction(
                "",
                IR.paramList(objectNames),
                IR.block(returnNode),
                type(StandardColors.TOP_OBJECT));
        compiler.reportChangeToChangeScope(iife);
        Node call = astFactory.createCall(iife, type(functionOrObjectLit), objectNamesForCall);
        call.putBooleanProp(Node.FREE_CALL, true);
        Node replacement;
        if (NodeUtil.isFunctionDeclaration(functionOrObjectLit)) {
          replacement =
              IR.var(IR.name(functionOrObjectLit.getFirstChild().getString()), call)
                  .srcrefTreeIfMissing(functionOrObjectLit);
        } else {
          replacement = call.srcrefTreeIfMissing(functionOrObjectLit);
        }
        functionOrObjectLit.replaceWith(replacement);
        returnNode.addChildToFront(functionOrObjectLit);
        compiler.reportChangeToEnclosingScope(replacement);
      }
    }

    /**
     * The initializer is pulled out and placed prior to the loop. The increment is updated with the
     * new loop object and property assignments
     */
    private void changeVanillaForLoopHeader(Node loopNode, Node updateLoopObject) {
      Node initializer = loopNode.getFirstChild();
      initializer.replaceWith(IR.empty());
      if (!initializer.isEmpty()) {
        if (!NodeUtil.isNameDeclaration(initializer)) {
          initializer = IR.exprResult(initializer).srcref(initializer);
        }
        addNodeBeforeLoop(initializer, loopNode);
      }

      Node increment = loopNode.getChildAtIndex(2);
      if (increment.isEmpty()) {
        increment.replaceWith(updateLoopObject.srcrefTreeIfMissing(loopNode));
      } else {
        Node placeHolder = IR.empty();
        increment.replaceWith(placeHolder);
        placeHolder.replaceWith(
            astFactory.createComma(updateLoopObject, increment).srcrefTreeIfMissing(loopNode));
      }
    }

    /**
     * For captured variables, change declarations to assignments on the corresponding field of the
     * introduced object. Rename all references accordingly.
     */
    private void changeDeclarationsToAssignments(Node loopNode, LoopObject loopObject) {
      for (Var var : loopObject.vars) {
        String newPropertyName = propertyNameMap.get(var);
        for (Node reference : referenceMap.get(var)) {
          // for-of loops are transpiled away before this pass runs
          checkState(!loopNode.isForOf(), loopNode);
          // For-of and for-in declarations are not altered, since they are
          // used as temporary variables for assignment.
          if (loopNode.isForIn() && loopNode.getFirstChild() == reference.getParent()) {
            assignLoopVarToLoopObjectProperty(
                loopNode, loopObject, var, newPropertyName, reference);
          } else {
            replaceDeclarationWithProperty(loopObject, newPropertyName, reference);
          }
        }
      }
    }

    /** Changes `for (let i = 0; ...)` into `for ($jscomp$loop$0.$jscomp$loop$prop$i$1 = 0; ...)` */
    private void replaceDeclarationWithProperty(
        LoopObject loopObject, String newPropertyName, Node reference) {
      if (NodeUtil.isNameDeclaration(reference.getParent())) {
        Node declaration = reference.getParent();
        Node grandParent = declaration.getParent();
        handleDeclarationList(declaration, grandParent);
        declaration = reference.getParent(); // Might have changed after normalization.
        // Change declaration to assignment, or just drop it if there's
        // no initial value.
        if (reference.hasChildren()) {
          Node newReference = cloneWithType(reference);
          Node assign = astFactory.createAssign(newReference, reference.removeFirstChild());
          extractInlineJSDoc(declaration, reference, declaration);
          maybeAddConstJSDoc(declaration, reference, declaration);
          assign.setJSDocInfo(declaration.getJSDocInfo());

          Node replacement = IR.exprResult(assign).srcrefTreeIfMissing(declaration);
          declaration.replaceWith(replacement);
          reference = newReference;
        } else {
          declaration.detach();
        }
        letConsts.remove(declaration);
        compiler.reportChangeToEnclosingScope(grandParent);
      }

      if (reference.getParent().isCall() && reference.getParent().getFirstChild() == reference) {
        reference.getParent().putBooleanProp(Node.FREE_CALL, false);
      }
      // Change reference to GETPROP.
      Node changeScope = NodeUtil.getEnclosingChangeScopeRoot(reference);
      reference.replaceWith(
          createLoopVarReferenceReplacement(loopObject, reference, newPropertyName));
      // TODO(johnlenz): Don't work on detached nodes.
      if (changeScope != null) {
        compiler.reportChangeToChangeScope(changeScope);
      }
    }

    /**
     * Transforms `for (const p in obj) { ... }`
     *
     * <p>into `for (const p in obj) { $jscomp$loop$0.$jscomp$loop$prop$0$p = p; ... }`
     */
    private void assignLoopVarToLoopObjectProperty(
        Node loopNode, LoopObject loopObject, Var var, String newPropertyName, Node reference) {
      // reference is the node loopVar in a for-in that looks like this:
      // `for (const loopVar in list) {`
      checkState(reference == var.getNameNode(), reference);
      Node referenceParent = reference.getParent();
      checkState(NodeUtil.isNameDeclaration(referenceParent), referenceParent);
      checkState(reference.isName(), reference);
      // Start transpiled form of
      // `for (const p in obj) { ... }`
      // with this statement to copy the loop variable into the corresponding loop object
      // property.
      // `$jscomp$loop$0.$jscomp$loop$prop$0$p = p;`
      Node loopVarReference = reference.cloneNode();
      // `$jscomp$loop$0.$jscomp$loop$prop$0$p = p;`
      final Node forInPropAssignmentStatemnt =
          IR.exprResult(
                  astFactory.createAssign(
                      createLoopVarReferenceReplacement(loopObject, reference, newPropertyName),
                      loopVarReference))
              .srcrefTreeIfMissing(reference);
      // The first statement in the body should be creating a new loop object value
      // $jscomp$loop$0 = {
      //    $jscomp$loop$prop$0$p: $jscomp$loop$0.$jscomp$loop$prop$0$p,
      //    $jscomp$loop$prop$0$otherVar: $jscomp$loop$0.$jscomp$loop$prop$0$p,
      //    // other property update assignments
      // }
      // We need to update the loop variable's value to it immediately after that
      final Node loopUpdateStatement =
          loopNode
              .getLastChild() // loop body
              .getFirstChild(); // first statement

      forInPropAssignmentStatemnt.insertAfter(loopUpdateStatement);
    }

    /** Rename all variables in the loop object to properties */
    private void renameVarsToProperties(LoopObject loopObject, Node objectLitNextIteration) {
      for (Var var : loopObject.vars) {
        String newPropertyName = propertyNameMap.get(var);
        objectLitNextIteration.addChildToBack(
            astFactory.createStringKey(
                newPropertyName,
                createLoopVarReferenceReplacement(loopObject, var.getNameNode(), newPropertyName)));
      }
    }

    /**
     * Creates a `$jscomp$loop$0.$jscomp$loop$prop$varName$1` replacement for a reference to
     * `varName`.
     */
    private Node createLoopVarReferenceReplacement(
        LoopObject loopObject, Node reference, String propertyName) {
      Node replacement =
          astFactory.createGetProp(
              createLoopObjectNameNode(loopObject), propertyName, type(reference));
      replacement.srcrefTree(reference);
      return replacement;
    }

    private Node createLoopObjectNameNode(LoopObject loopObject) {
      return astFactory.createName(loopObject.name, type(StandardColors.TOP_OBJECT));
    }

    private class LoopObject {

      /**
       * The name of the variable having the loop's internal variables as properties, and the label
       * applied to the block containing the original loop body in cases where these are needed.
       */
      private final String name;

      private final Set<Var> vars = new LinkedHashSet<>();

      private LoopObject(String name) {
        this.name = name;
      }
    }
  }

  private Node cloneWithType(Node node) {
    Node clone = node.cloneNode();
    if (astFactory.isAddingColors()) {
      clone.setColor(node.getColor());
    }
    return clone;
  }
}
