package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

class LoxInstance {
  // Null only for a metaclass, which is the end of the "class of" chain.
  LoxClass klass;
  private final Map<String, Object> fields = new HashMap<>();

  LoxInstance(LoxClass klass) {
    this.klass = klass;
  }

  // Chapter 12, challenge 2: takes the interpreter so a getter can run
  // as soon as it is accessed.
  Object get(Token name, Interpreter interpreter) {
    if (fields.containsKey(name.lexeme)) {
      return fields.get(name.lexeme);
    }

    LoxFunction method =
        klass == null ? null : klass.findMethod(name.lexeme);
    if (method != null) {
      LoxFunction bound = method.bind(this);
      if (bound.isGetter()) {
        return bound.call(interpreter, new ArrayList<>());
      }
      return bound;
    }

    throw new RuntimeError(name,
        "Undefined property '" + name.lexeme + "'.");
  }

  void set(Token name, Object value) {
    fields.put(name.lexeme, value);
  }

  @Override
  public String toString() {
    return klass.name + " instance";
  }
}
