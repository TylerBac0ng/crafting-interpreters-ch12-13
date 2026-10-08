package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class Interpreter implements Expr.Visitor<Object>,
                             Stmt.Visitor<Void> {
  // Chapter 8, challenge 2: a sentinel stored in a variable declared
  // without an initializer, so we can tell "never assigned" apart from
  // "explicitly assigned nil".
  private static final Object uninitialized = new Object();

  // Chapter 9, challenge 3: thrown by a break statement and caught by
  // the nearest enclosing while loop. No stack trace is needed since it
  // is used for control flow, not error reporting.
  private static class BreakException extends RuntimeException {
    BreakException() {
      super(null, null, false, false);
    }
  }

  // Chapter 11, challenge 4: where the resolver found a local variable.
  // "depth" is how many scopes to walk out, "index" is its slot there.
  private static class Slot {
    final int depth;
    final int index;

    Slot(int depth, int index) {
      this.depth = depth;
      this.index = index;
    }
  }

  // Globals are still looked up by name, since a function body may refer
  // to a global that is declared later in the file.
  final Map<String, Object> globals = new HashMap<>();
  // The outermost environment. It holds no slots; it only marks "global
  // scope" and ends every chain of enclosing environments.
  private final Environment globalEnvironment = new Environment();
  private Environment environment = globalEnvironment;
  private final Map<Expr, Slot> locals = new HashMap<>();

  Interpreter() {
    globals.put("clock", new LoxCallable() {
      @Override
      public int arity() { return 0; }

      @Override
      public Object call(Interpreter interpreter,
                         List<Object> arguments) {
        return (double)System.currentTimeMillis() / 1000.0;
      }

      @Override
      public String toString() { return "<native fn>"; }
    });
  }

  void interpret(List<Stmt> statements) {
    try {
      for (Stmt statement : statements) {
        execute(statement);
      }
    } catch (RuntimeError error) {
      Lox.runtimeError(error);
    }
  }

  // Chapter 8, challenge 1: evaluates a bare REPL expression and returns
  // its value as a string, or null if a runtime error occurred.
  String interpret(Expr expression) {
    try {
      Object value = evaluate(expression);
      return stringify(value);
    } catch (RuntimeError error) {
      Lox.runtimeError(error);
      return null;
    }
  }

  private Object evaluate(Expr expr) {
    return expr.accept(this);
  }

  private void execute(Stmt stmt) {
    stmt.accept(this);
  }

  void resolve(Expr expr, int depth, int index) {
    locals.put(expr, new Slot(depth, index));
  }

  void executeBlock(List<Stmt> statements,
                    Environment environment) {
    Environment previous = this.environment;
    try {
      this.environment = environment;

      for (Stmt statement : statements) {
        execute(statement);
      }
    } finally {
      this.environment = previous;
    }
  }

  @Override
  public Void visitBlockStmt(Stmt.Block stmt) {
    executeBlock(stmt.statements, new Environment(environment));
    return null;
  }

  @Override
  public Void visitBreakStmt(Stmt.Break stmt) {
    throw new BreakException();
  }

  @Override
  public Void visitClassStmt(Stmt.Class stmt) {
    LoxClass superclass = null;
    if (stmt.superclass != null) {
      Object value = evaluate(stmt.superclass);
      if (!(value instanceof LoxClass)) {
        throw new RuntimeError(stmt.superclass.name,
            "Superclass must be a class.");
      }
      superclass = (LoxClass)value;
    }

    // Chapter 13, challenge 1: start from the traits' methods; the
    // class's own methods are added on top and win over them.
    Map<String, LoxFunction> methods = new HashMap<>();
    Map<String, LoxFunction> classMethods = new HashMap<>();
    applyTraits(stmt.traits, stmt.methods, stmt.classMethods,
        methods, classMethods);

    // "super" is slot 0 of an environment around the methods. Static
    // methods see the superclass's metaclass as "super", so super.foo()
    // in a static method finds the superclass's static foo().
    Environment methodClosure = environment;
    Environment classMethodClosure = environment;
    if (superclass != null) {
      methodClosure = new Environment(environment);
      methodClosure.define(superclass);
      classMethodClosure = new Environment(environment);
      classMethodClosure.define(superclass.klass);
    }

    defineMethods(stmt.methods, methodClosure, methods, true);
    defineMethods(stmt.classMethods, classMethodClosure, classMethods,
        false);

    // Chapter 12, challenge 1: the metaclass holds the static methods.
    // Its superclass is the superclass's metaclass, so static methods are
    // inherited too.
    LoxClass metaclass = new LoxClass(null, stmt.name.lexeme + " metaclass",
        superclass == null ? null : superclass.klass, classMethods);
    LoxClass klass = new LoxClass(metaclass, stmt.name.lexeme,
        superclass, methods);

    // Nothing has been defined in this environment since the resolver
    // gave the class name its slot, so it lands at the right index.
    declare(stmt.name, klass);
    return null;
  }

  // Chapter 13, challenge 1.
  @Override
  public Void visitTraitStmt(Stmt.Trait stmt) {
    Map<String, LoxFunction> methods = new HashMap<>();
    Map<String, LoxFunction> classMethods = new HashMap<>();
    applyTraits(stmt.traits, stmt.methods, stmt.classMethods,
        methods, classMethods);

    defineMethods(stmt.methods, environment, methods, true);
    defineMethods(stmt.classMethods, environment, classMethods, false);

    declare(stmt.name, new LoxTrait(stmt.name, methods, classMethods));
    return null;
  }

  private void defineMethods(List<Stmt.Function> declarations,
                             Environment closure,
                             Map<String, LoxFunction> methods,
                             boolean canBeInitializer) {
    for (Stmt.Function method : declarations) {
      boolean isInitializer =
          canBeInitializer && method.name.lexeme.equals("init");
      methods.put(method.name.lexeme, new LoxFunction(method.name.lexeme,
          method.function, closure, isInitializer));
    }
  }

  // Chapter 13, challenge 1: copies every trait's methods into the maps.
  // Two traits providing the same method is a conflict, unless the class
  // (or trait) being declared defines that method itself to settle it.
  private void applyTraits(List<Expr.Variable> traitExprs,
                           List<Stmt.Function> ownMethods,
                           List<Stmt.Function> ownClassMethods,
                           Map<String, LoxFunction> methods,
                           Map<String, LoxFunction> classMethods) {
    Map<String, Token> providers = new HashMap<>();
    Map<String, Token> classProviders = new HashMap<>();
    for (Expr.Variable traitExpr : traitExprs) {
      Object value = evaluate(traitExpr);
      if (!(value instanceof LoxTrait)) {
        throw new RuntimeError(traitExpr.name,
            "'" + traitExpr.name.lexeme + "' is not a trait.");
      }

      LoxTrait trait = (LoxTrait)value;
      addTraitMethods(traitExpr.name, trait.methods, ownMethods,
          methods, providers);
      addTraitMethods(traitExpr.name, trait.classMethods, ownClassMethods,
          classMethods, classProviders);
    }
  }

  private void addTraitMethods(Token trait, Map<String, LoxFunction> from,
                               List<Stmt.Function> own,
                               Map<String, LoxFunction> into,
                               Map<String, Token> providers) {
    for (Map.Entry<String, LoxFunction> entry : from.entrySet()) {
      String name = entry.getKey();
      Token other = providers.get(name);
      // The same method reached through two traits (both use a third
      // trait) is not a conflict.
      if (other != null && into.get(name) != entry.getValue() &&
          !definesMethod(own, name)) {
        throw new RuntimeError(trait, "Method '" + name +
            "' is provided by both '" + other.lexeme + "' and '" +
            trait.lexeme + "'. Define it in the class to resolve the " +
            "conflict.");
      }
      providers.put(name, trait);
      into.put(name, entry.getValue());
    }
  }

  private static boolean definesMethod(List<Stmt.Function> methods,
                                       String name) {
    for (Stmt.Function method : methods) {
      if (method.name.lexeme.equals(name)) return true;
    }
    return false;
  }

  @Override
  public Void visitExpressionStmt(Stmt.Expression stmt) {
    evaluate(stmt.expression);
    return null;
  }

  @Override
  public Void visitFunctionStmt(Stmt.Function stmt) {
    LoxFunction function = new LoxFunction(stmt.name.lexeme,
        stmt.function, environment, false);
    declare(stmt.name, function);
    return null;
  }

  @Override
  public Void visitIfStmt(Stmt.If stmt) {
    if (isTruthy(evaluate(stmt.condition))) {
      execute(stmt.thenBranch);
    } else if (stmt.elseBranch != null) {
      execute(stmt.elseBranch);
    }
    return null;
  }

  @Override
  public Void visitPrintStmt(Stmt.Print stmt) {
    Object value = evaluate(stmt.expression);
    System.out.println(stringify(value));
    return null;
  }

  @Override
  public Void visitReturnStmt(Stmt.Return stmt) {
    Object value = null;
    if (stmt.value != null) value = evaluate(stmt.value);

    throw new Return(value);
  }

  @Override
  public Void visitVarStmt(Stmt.Var stmt) {
    Object value = uninitialized;
    if (stmt.initializer != null) {
      value = evaluate(stmt.initializer);
    }

    declare(stmt.name, value);
    return null;
  }

  // Chapter 11, challenge 4: a global goes in the map by name. A local is
  // appended to the current scope's array, which puts it at the index the
  // resolver gave it.
  private void declare(Token name, Object value) {
    if (environment == globalEnvironment) {
      globals.put(name.lexeme, value);
    } else {
      environment.define(value);
    }
  }

  @Override
  public Void visitWhileStmt(Stmt.While stmt) {
    try {
      while (isTruthy(evaluate(stmt.condition))) {
        execute(stmt.body);
      }
    } catch (BreakException ex) {
      // Exit the loop.
    }
    return null;
  }

  @Override
  public Object visitAssignExpr(Expr.Assign expr) {
    Object value = evaluate(expr.value);

    Slot slot = locals.get(expr);
    if (slot != null) {
      environment.assignAt(slot.depth, slot.index, value);
    } else if (globals.containsKey(expr.name.lexeme)) {
      globals.put(expr.name.lexeme, value);
    } else {
      throw new RuntimeError(expr.name,
          "Undefined variable '" + expr.name.lexeme + "'.");
    }

    return value;
  }

  @Override
  public Object visitVariableExpr(Expr.Variable expr) {
    Object value = lookUpVariable(expr.name, expr);
    if (value == uninitialized) {
      throw new RuntimeError(expr.name,
          "Variable '" + expr.name.lexeme +
          "' must be initialized before use.");
    }
    return value;
  }

  private Object lookUpVariable(Token name, Expr expr) {
    Slot slot = locals.get(expr);
    if (slot != null) {
      return environment.getAt(slot.depth, slot.index);
    }

    if (globals.containsKey(name.lexeme)) {
      return globals.get(name.lexeme);
    }

    throw new RuntimeError(name,
        "Undefined variable '" + name.lexeme + "'.");
  }

  @Override
  public Object visitLiteralExpr(Expr.Literal expr) {
    return expr.value;
  }

  @Override
  public Object visitLogicalExpr(Expr.Logical expr) {
    Object left = evaluate(expr.left);

    if (expr.operator.type == TokenType.OR) {
      if (isTruthy(left)) return left;
    } else {
      if (!isTruthy(left)) return left;
    }

    return evaluate(expr.right);
  }

  @Override
  public Object visitCallExpr(Expr.Call expr) {
    Object callee = evaluate(expr.callee);

    List<Object> arguments = new ArrayList<>();
    for (Expr argument : expr.arguments) {
      arguments.add(evaluate(argument));
    }

    if (!(callee instanceof LoxCallable)) {
      throw new RuntimeError(expr.paren,
          "Can only call functions and classes.");
    }

    LoxCallable function = (LoxCallable)callee;
    if (arguments.size() != function.arity()) {
      throw new RuntimeError(expr.paren, "Expected " +
          function.arity() + " arguments but got " +
          arguments.size() + ".");
    }

    return function.call(this, arguments);
  }

  // Chapter 10, challenge 2: an anonymous function closes over the
  // environment where the expression is evaluated.
  @Override
  public Object visitFunctionExpr(Expr.Function expr) {
    return new LoxFunction(null, expr, environment, false);
  }

  @Override
  public Object visitGetExpr(Expr.Get expr) {
    Object object = evaluate(expr.object);
    if (object instanceof LoxInstance) {
      return ((LoxInstance) object).get(expr.name, this);
    }

    // Chapter 13, challenge 3.
    if (object instanceof LoxList) {
      return ((LoxList) object).getProperty(expr.name);
    }

    throw new RuntimeError(expr.name,
        "Only instances have properties.");
  }

  @Override
  public Object visitSetExpr(Expr.Set expr) {
    Object object = evaluate(expr.object);

    if (!(object instanceof LoxInstance)) {
      throw new RuntimeError(expr.name,
          "Only instances have fields.");
    }

    Object value = evaluate(expr.value);
    ((LoxInstance)object).set(expr.name, value);
    return value;
  }

  // Chapter 11, challenge 4: "super" is slot 0 of its environment, and
  // "this" is slot 0 of the environment just inside it.
  @Override
  public Object visitSuperExpr(Expr.Super expr) {
    Slot slot = locals.get(expr);
    LoxClass superclass = (LoxClass)environment.getAt(slot.depth, 0);
    LoxInstance object =
        (LoxInstance)environment.getAt(slot.depth - 1, 0);

    LoxFunction method = superclass.findMethod(expr.method.lexeme);

    if (method == null) {
      throw new RuntimeError(expr.method,
          "Undefined property '" + expr.method.lexeme + "'.");
    }

    LoxFunction bound = method.bind(object);
    // Chapter 12, challenge 2: super.area runs the superclass getter.
    if (bound.isGetter()) return bound.call(this, new ArrayList<>());
    return bound;
  }

  @Override
  public Object visitThisExpr(Expr.This expr) {
    return lookUpVariable(expr.keyword, expr);
  }

  // Chapter 13, challenge 2: "inner" is a local the resolver put next to
  // "this".
  @Override
  public Object visitInnerExpr(Expr.Inner expr) {
    return lookUpVariable(expr.keyword, expr);
  }

  // Chapter 13, challenge 3.
  @Override
  public Object visitListLiteralExpr(Expr.ListLiteral expr) {
    List<Object> elements = new ArrayList<>();
    for (Expr element : expr.elements) {
      elements.add(evaluate(element));
    }
    return new LoxList(elements);
  }

  @Override
  public Object visitIndexExpr(Expr.Index expr) {
    Object object = evaluate(expr.object);
    Object index = evaluate(expr.index);

    if (object instanceof LoxList) {
      return ((LoxList)object).get(checkIndex(expr.bracket, index),
          expr.bracket);
    }

    if (object instanceof String) {
      String string = (String)object;
      int i = checkIndex(expr.bracket, index);
      if (i < 0 || i >= string.length()) {
        throw new RuntimeError(expr.bracket, "String index " + i +
            " out of range for length " + string.length() + ".");
      }
      return string.substring(i, i + 1);
    }

    throw new RuntimeError(expr.bracket,
        "Can only index lists and strings.");
  }

  @Override
  public Object visitIndexSetExpr(Expr.IndexSet expr) {
    Object object = evaluate(expr.object);
    Object index = evaluate(expr.index);
    Object value = evaluate(expr.value);

    if (!(object instanceof LoxList)) {
      throw new RuntimeError(expr.bracket,
          "Can only assign to list elements.");
    }

    ((LoxList)object).set(checkIndex(expr.bracket, index), value,
        expr.bracket);
    return value;
  }

  private int checkIndex(Token bracket, Object index) {
    if (!(index instanceof Double) ||
        (double)index != Math.floor((double)index)) {
      throw new RuntimeError(bracket, "Index must be an integer.");
    }
    return (int)(double)index;
  }

  @Override
  public Object visitGroupingExpr(Expr.Grouping expr) {
    return evaluate(expr.expression);
  }

  @Override
  public Object visitUnaryExpr(Expr.Unary expr) {
    Object right = evaluate(expr.right);

    switch (expr.operator.type) {
      case BANG:
        return !isTruthy(right);
      case MINUS:
        checkNumberOperand(expr.operator, right);
        return -(double)right;
    }

    // Unreachable.
    return null;
  }

  @Override
  public Object visitBinaryExpr(Expr.Binary expr) {
    Object left = evaluate(expr.left);
    Object right = evaluate(expr.right);

    switch (expr.operator.type) {
      case GREATER:
        checkNumberOperands(expr.operator, left, right);
        return (double)left > (double)right;
      case GREATER_EQUAL:
        checkNumberOperands(expr.operator, left, right);
        return (double)left >= (double)right;
      case LESS:
        checkNumberOperands(expr.operator, left, right);
        return (double)left < (double)right;
      case LESS_EQUAL:
        checkNumberOperands(expr.operator, left, right);
        return (double)left <= (double)right;
      case BANG_EQUAL: return !isEqual(left, right);
      case EQUAL_EQUAL: return isEqual(left, right);
      case MINUS:
        checkNumberOperands(expr.operator, left, right);
        return (double)left - (double)right;
      case PLUS:
        if (left instanceof Double && right instanceof Double) {
          return (double)left + (double)right;
        }

        if (left instanceof String && right instanceof String) {
          return (String)left + (String)right;
        }

        throw new RuntimeError(expr.operator,
            "Operands must be two numbers or two strings.");
      case SLASH:
        checkNumberOperands(expr.operator, left, right);
        return (double)left / (double)right;
      case STAR:
        checkNumberOperands(expr.operator, left, right);
        return (double)left * (double)right;
    }

    // Unreachable.
    return null;
  }

  private void checkNumberOperand(Token operator, Object operand) {
    if (operand instanceof Double) return;
    throw new RuntimeError(operator, "Operand must be a number.");
  }

  private void checkNumberOperands(Token operator,
                                   Object left, Object right) {
    if (left instanceof Double && right instanceof Double) return;

    throw new RuntimeError(operator, "Operands must be numbers.");
  }

  static boolean isTruthy(Object object) {
    if (object == null) return false;
    if (object instanceof Boolean) return (boolean)object;
    return true;
  }

  private boolean isEqual(Object a, Object b) {
    if (a == null && b == null) return true;
    if (a == null) return false;

    return a.equals(b);
  }

  private String stringify(Object object) {
    if (object == null) return "nil";

    // Chapter 13, challenge 3.
    if (object instanceof LoxList) {
      StringBuilder builder = new StringBuilder("[");
      List<Object> elements = ((LoxList)object).elements;
      for (int i = 0; i < elements.size(); i++) {
        if (i > 0) builder.append(", ");
        Object element = elements.get(i);
        builder.append(element == object ? "[...]" : stringify(element));
      }
      return builder.append("]").toString();
    }

    if (object instanceof Double) {
      String text = object.toString();
      if (text.endsWith(".0")) {
        text = text.substring(0, text.length() - 2);
      }
      return text;
    }

    return object.toString();
  }
}
