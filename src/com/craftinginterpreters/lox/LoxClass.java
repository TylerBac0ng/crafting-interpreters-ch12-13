package com.craftinginterpreters.lox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Chapter 12, challenge 1: a class is itself an instance, of its
// metaclass. Static methods are the metaclass's methods, so
// "Math.square(3)" uses the same property lookup as "point.length()",
// and static fields ("Math.pi = 3.14;") work for free.
class LoxClass extends LoxInstance implements LoxCallable {
  final String name;
  final LoxClass superclass;
  final Map<String, LoxFunction> methods = new HashMap<>();

  LoxClass(LoxClass metaclass, String name, LoxClass superclass,
           Map<String, LoxFunction> methods) {
    super(metaclass);
    this.name = name;
    this.superclass = superclass;
    // Chapter 13, challenge 1: the same function may come from a trait
    // used by several classes, so each class gets its own copy that knows
    // which class it belongs to (needed by "inner").
    for (Map.Entry<String, LoxFunction> entry : methods.entrySet()) {
      this.methods.put(entry.getKey(), entry.getValue().withOwner(this));
    }
  }

  // Chapter 13, challenge 2: BETA-style lookup. The method in the class
  // highest up the hierarchy wins; a subclass's version only runs when
  // the superclass's method calls inner().
  LoxFunction findMethod(String name) {
    if (superclass != null) {
      LoxFunction method = superclass.findMethod(name);
      if (method != null) return method;
    }

    return methods.get(name);
  }

  // Chapter 13, challenge 2: the method that refines owner's version of
  // "name" for an instance of this class. It is the highest class below
  // owner, on the path from this class up to owner, that defines "name".
  LoxFunction findInner(LoxClass owner, String name) {
    LoxFunction refinement = null;
    for (LoxClass klass = this; klass != null; klass = klass.superclass) {
      if (klass == owner) return refinement;
      LoxFunction method = klass.methods.get(name);
      if (method != null) refinement = method;
    }

    // Owner is not an ancestor. Can't happen for a bound method.
    return null;
  }

  @Override
  public int arity() {
    LoxFunction initializer = findMethod("init");
    if (initializer == null) return 0;
    return initializer.arity();
  }

  @Override
  public Object call(Interpreter interpreter, List<Object> arguments) {
    LoxInstance instance = new LoxInstance(this);
    LoxFunction initializer = findMethod("init");
    if (initializer != null) {
      initializer.bind(instance).call(interpreter, arguments);
    }

    return instance;
  }

  @Override
  public String toString() {
    return name;
  }
}
