package com.craftinginterpreters.lox;

import java.util.List;

class LoxFunction implements LoxCallable {
  // Chapter 10, challenge 2: null for an anonymous function.
  private final String name;
  private final Expr.Function declaration;
  private final Environment closure;
  private final boolean isInitializer;
  // Chapter 13, challenge 2: the class this method belongs to, so inner()
  // knows where to continue looking. Null for a plain function.
  private final LoxClass owner;

  LoxFunction(String name, Expr.Function declaration,
              Environment closure, boolean isInitializer) {
    this(name, declaration, closure, isInitializer, null);
  }

  private LoxFunction(String name, Expr.Function declaration,
                      Environment closure, boolean isInitializer,
                      LoxClass owner) {
    this.name = name;
    this.declaration = declaration;
    this.closure = closure;
    this.isInitializer = isInitializer;
    this.owner = owner;
  }

  LoxFunction withOwner(LoxClass owner) {
    return new LoxFunction(name, declaration, closure, isInitializer,
        owner);
  }

  // Chapter 11, challenge 4: "this" is slot 0 of the bound environment.
  // Chapter 13, challenge 2: "inner" is slot 1, matching the resolver.
  LoxFunction bind(LoxInstance instance) {
    Environment environment = new Environment(closure);
    environment.define(instance);
    environment.define(new Inner(instance));
    return new LoxFunction(name, declaration, environment, isInitializer,
        owner);
  }

  // Chapter 12, challenge 2.
  boolean isGetter() {
    return declaration.params == null;
  }

  @Override
  public int arity() {
    if (isGetter()) return 0;
    return declaration.params.size();
  }

  @Override
  public Object call(Interpreter interpreter,
                     List<Object> arguments) {
    Environment environment = new Environment(closure);
    // Chapter 11, challenge 4: parameters fill slots 0..n-1, in the same
    // order the resolver declared them.
    for (Object argument : arguments) {
      environment.define(argument);
    }

    try {
      interpreter.executeBlock(declaration.body, environment);
    } catch (Return returnValue) {
      if (isInitializer) return closure.getAt(0, 0);

      return returnValue.value;
    }

    if (isInitializer) return closure.getAt(0, 0);
    return null;
  }

  @Override
  public String toString() {
    if (name == null) return "<fn>";
    return "<fn " + name + ">";
  }

  // Chapter 13, challenge 2: the value of "inner" inside a method. Calling
  // it runs the next subclass's version of the same method on the same
  // instance, or does nothing and returns nil if no subclass refines it.
  private class Inner implements LoxCallable {
    private final LoxInstance instance;
    private LoxFunction refinement;
    private boolean found = false;

    Inner(LoxInstance instance) {
      this.instance = instance;
    }

    // Looked up on first use, since most methods never call inner().
    private LoxFunction refinement() {
      if (!found) {
        LoxFunction method = instance.klass.findInner(owner, name);
        if (method != null) refinement = method.bind(instance);
        found = true;
      }
      return refinement;
    }

    @Override
    public int arity() {
      // With no refinement, inner() accepts the same arguments as the
      // method it is in, and ignores them.
      if (refinement() == null) return LoxFunction.this.arity();
      return refinement().arity();
    }

    @Override
    public Object call(Interpreter interpreter, List<Object> arguments) {
      if (refinement() == null) return null;
      return refinement().call(interpreter, arguments);
    }

    @Override
    public String toString() {
      return "<inner " + name + ">";
    }
  }
}
