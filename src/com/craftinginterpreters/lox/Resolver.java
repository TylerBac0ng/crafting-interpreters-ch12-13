package com.craftinginterpreters.lox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

class Resolver implements Expr.Visitor<Void>, Stmt.Visitor<Void> {
  private enum FunctionType {
    NONE,
    FUNCTION,
    INITIALIZER,
    METHOD
  }

  private enum ClassType {
    NONE,
    CLASS,
    SUBCLASS,
    TRAIT // Chapter 13, challenge 1.
  }

  // What the resolver tracks for each local variable. The book only stores
  // a "defined" flag; the other fields are for the challenges.
  private static class Variable {
    final Token name;
    // Chapter 11, challenge 4: the variable's slot in its scope's array.
    final int index;
    boolean defined = false;
    // Chapter 11, challenge 3: set when the variable's value is read.
    boolean used = false;

    Variable(Token name, int index) {
      this.name = name;
      this.index = index;
    }
  }

  private final Interpreter interpreter;
  private final Stack<Map<String, Variable>> scopes = new Stack<>();
  private FunctionType currentFunction = FunctionType.NONE;
  private ClassType currentClass = ClassType.NONE;

  Resolver(Interpreter interpreter) {
    this.interpreter = interpreter;
  }

  void resolve(List<Stmt> statements) {
    for (Stmt statement : statements) {
      resolve(statement);
    }
  }

  // Used by the REPL to resolve a bare expression.
  void resolve(Expr expr) {
    expr.accept(this);
  }

  @Override
  public Void visitBlockStmt(Stmt.Block stmt) {
    beginScope();
    resolve(stmt.statements);
    endScope();
    return null;
  }

  @Override
  public Void visitBreakStmt(Stmt.Break stmt) {
    return null;
  }

  @Override
  public Void visitClassStmt(Stmt.Class stmt) {
    ClassType enclosingClass = currentClass;
    currentClass = ClassType.CLASS;

    declare(stmt.name);
    define(stmt.name);

    if (stmt.superclass != null &&
        stmt.name.lexeme.equals(stmt.superclass.name.lexeme)) {
      Lox.error(stmt.superclass.name,
          "A class can't inherit from itself.");
    }

    if (stmt.superclass != null) {
      currentClass = ClassType.SUBCLASS;
      resolve(stmt.superclass);
    }

    resolveTraits(stmt.name, stmt.traits);

    if (stmt.superclass != null) {
      beginScope();
      declareImplicit("super");
    }

    resolveMethods(stmt.methods, true);
    resolveMethods(stmt.classMethods, false);

    if (stmt.superclass != null) endScope();

    currentClass = enclosingClass;
    return null;
  }

  // Chapter 13, challenge 1: like a class with no superclass, except
  // "super" is not allowed.
  @Override
  public Void visitTraitStmt(Stmt.Trait stmt) {
    ClassType enclosingClass = currentClass;
    currentClass = ClassType.TRAIT;

    declare(stmt.name);
    define(stmt.name);

    resolveTraits(stmt.name, stmt.traits);
    resolveMethods(stmt.methods, true);
    resolveMethods(stmt.classMethods, false);

    currentClass = enclosingClass;
    return null;
  }

  private void resolveTraits(Token name, List<Expr.Variable> traits) {
    for (Expr.Variable trait : traits) {
      if (trait.name.lexeme.equals(name.lexeme)) {
        Lox.error(trait.name, "A class or trait can't use itself.");
      }
      resolve(trait);
    }
  }

  // Each method gets a scope holding "this" (slot 0) and "inner" (slot 1,
  // chapter 13 challenge 2), the same layout LoxFunction.bind() creates.
  private void resolveMethods(List<Stmt.Function> methods,
                              boolean instanceMethods) {
    for (Stmt.Function method : methods) {
      FunctionType declaration = FunctionType.METHOD;
      if (instanceMethods && method.name.lexeme.equals("init")) {
        declaration = FunctionType.INITIALIZER;
        // Chapter 12, challenge 2.
        if (method.function.params == null) {
          Lox.error(method.name, "Can't use 'init' as a getter.");
        }
      }

      beginScope();
      declareImplicit("this");
      declareImplicit("inner");
      resolveFunction(method.function, declaration);
      endScope();
    }
  }

  // Declares a name the user can't write as a variable (it is a keyword),
  // so it is never reported as unused.
  private void declareImplicit(String name) {
    Map<String, Variable> scope = scopes.peek();
    Variable variable = new Variable(null, scope.size());
    variable.defined = true;
    variable.used = true;
    scope.put(name, variable);
  }

  @Override
  public Void visitExpressionStmt(Stmt.Expression stmt) {
    resolve(stmt.expression);
    return null;
  }

  @Override
  public Void visitFunctionStmt(Stmt.Function stmt) {
    declare(stmt.name);
    define(stmt.name);

    resolveFunction(stmt.function, FunctionType.FUNCTION);
    return null;
  }

  @Override
  public Void visitIfStmt(Stmt.If stmt) {
    resolve(stmt.condition);
    resolve(stmt.thenBranch);
    if (stmt.elseBranch != null) resolve(stmt.elseBranch);
    return null;
  }

  @Override
  public Void visitPrintStmt(Stmt.Print stmt) {
    resolve(stmt.expression);
    return null;
  }

  @Override
  public Void visitReturnStmt(Stmt.Return stmt) {
    if (currentFunction == FunctionType.NONE) {
      Lox.error(stmt.keyword, "Can't return from top-level code.");
    }

    if (stmt.value != null) {
      if (currentFunction == FunctionType.INITIALIZER) {
        Lox.error(stmt.keyword,
            "Can't return a value from an initializer.");
      }

      resolve(stmt.value);
    }

    return null;
  }

  @Override
  public Void visitVarStmt(Stmt.Var stmt) {
    declare(stmt.name);
    if (stmt.initializer != null) {
      resolve(stmt.initializer);
    }
    define(stmt.name);
    return null;
  }

  @Override
  public Void visitWhileStmt(Stmt.While stmt) {
    resolve(stmt.condition);
    resolve(stmt.body);
    return null;
  }

  @Override
  public Void visitAssignExpr(Expr.Assign expr) {
    resolve(expr.value);
    resolveLocal(expr, expr.name, false);
    return null;
  }

  @Override
  public Void visitBinaryExpr(Expr.Binary expr) {
    resolve(expr.left);
    resolve(expr.right);
    return null;
  }

  @Override
  public Void visitCallExpr(Expr.Call expr) {
    resolve(expr.callee);

    for (Expr argument : expr.arguments) {
      resolve(argument);
    }

    return null;
  }

  // Chapter 10, challenge 2: an anonymous function has no name to declare.
  @Override
  public Void visitFunctionExpr(Expr.Function expr) {
    resolveFunction(expr, FunctionType.FUNCTION);
    return null;
  }

  @Override
  public Void visitGetExpr(Expr.Get expr) {
    resolve(expr.object);
    return null;
  }

  @Override
  public Void visitSetExpr(Expr.Set expr) {
    resolve(expr.value);
    resolve(expr.object);
    return null;
  }

  @Override
  public Void visitSuperExpr(Expr.Super expr) {
    if (currentClass == ClassType.NONE) {
      Lox.error(expr.keyword,
          "Can't use 'super' outside of a class.");
    } else if (currentClass == ClassType.TRAIT) {
      Lox.error(expr.keyword, "Can't use 'super' in a trait.");
    } else if (currentClass != ClassType.SUBCLASS) {
      Lox.error(expr.keyword,
          "Can't use 'super' in a class with no superclass.");
    }

    resolveLocal(expr, expr.keyword, true);
    return null;
  }

  @Override
  public Void visitThisExpr(Expr.This expr) {
    if (currentClass == ClassType.NONE) {
      Lox.error(expr.keyword,
          "Can't use 'this' outside of a class.");
      return null;
    }

    resolveLocal(expr, expr.keyword, true);
    return null;
  }

  // Chapter 13, challenge 2.
  @Override
  public Void visitInnerExpr(Expr.Inner expr) {
    if (currentClass == ClassType.NONE) {
      Lox.error(expr.keyword,
          "Can't use 'inner' outside of a class.");
      return null;
    }

    resolveLocal(expr, expr.keyword, true);
    return null;
  }

  // Chapter 13, challenge 3.
  @Override
  public Void visitListLiteralExpr(Expr.ListLiteral expr) {
    for (Expr element : expr.elements) {
      resolve(element);
    }
    return null;
  }

  @Override
  public Void visitIndexExpr(Expr.Index expr) {
    resolve(expr.object);
    resolve(expr.index);
    return null;
  }

  @Override
  public Void visitIndexSetExpr(Expr.IndexSet expr) {
    resolve(expr.object);
    resolve(expr.index);
    resolve(expr.value);
    return null;
  }

  @Override
  public Void visitGroupingExpr(Expr.Grouping expr) {
    resolve(expr.expression);
    return null;
  }

  @Override
  public Void visitLiteralExpr(Expr.Literal expr) {
    return null;
  }

  @Override
  public Void visitLogicalExpr(Expr.Logical expr) {
    resolve(expr.left);
    resolve(expr.right);
    return null;
  }

  @Override
  public Void visitUnaryExpr(Expr.Unary expr) {
    resolve(expr.right);
    return null;
  }

  @Override
  public Void visitVariableExpr(Expr.Variable expr) {
    if (!scopes.isEmpty()) {
      Variable variable = scopes.peek().get(expr.name.lexeme);
      if (variable != null && !variable.defined) {
        Lox.error(expr.name,
            "Can't read local variable in its own initializer.");
      }
    }

    resolveLocal(expr, expr.name, true);
    return null;
  }

  private void resolve(Stmt stmt) {
    stmt.accept(this);
  }

  private void resolveFunction(Expr.Function function,
                               FunctionType type) {
    FunctionType enclosingFunction = currentFunction;
    currentFunction = type;

    beginScope();
    // Chapter 12, challenge 2: a getter has no parameter list.
    if (function.params != null) {
      for (Token param : function.params) {
        declare(param);
        define(param);
        // Chapter 11, challenge 3: an unused parameter is not an error. A
        // callback often has to accept arguments it does not need.
        scopes.peek().get(param.lexeme).used = true;
      }
    }
    resolve(function.body);
    endScope();

    currentFunction = enclosingFunction;
  }

  private void beginScope() {
    // LinkedHashMap so unused-variable errors come out in source order.
    scopes.push(new LinkedHashMap<String, Variable>());
  }

  private void endScope() {
    Map<String, Variable> scope = scopes.pop();

    // Chapter 11, challenge 3: report locals whose value is never read.
    for (Variable variable : scope.values()) {
      if (!variable.used) {
        Lox.error(variable.name, "Local variable '" +
            variable.name.lexeme + "' is never used.");
      }
    }
  }

  private void declare(Token name) {
    if (scopes.isEmpty()) return;

    Map<String, Variable> scope = scopes.peek();
    if (scope.containsKey(name.lexeme)) {
      Lox.error(name,
          "Already a variable with this name in this scope.");
      return;
    }

    // Chapter 11, challenge 4: slots are numbered in declaration order,
    // which is the order the interpreter will define them at runtime.
    scope.put(name.lexeme, new Variable(name, scope.size()));
  }

  private void define(Token name) {
    if (scopes.isEmpty()) return;
    scopes.peek().get(name.lexeme).defined = true;
  }

  private void resolveLocal(Expr expr, Token name, boolean isRead) {
    for (int i = scopes.size() - 1; i >= 0; i--) {
      Variable variable = scopes.get(i).get(name.lexeme);
      if (variable != null) {
        // Chapter 11, challenge 4: store the slot index with the depth.
        interpreter.resolve(expr, scopes.size() - 1 - i, variable.index);
        if (isRead) variable.used = true;
        return;
      }
    }

    // Not found. Assume it is global.
  }
}
