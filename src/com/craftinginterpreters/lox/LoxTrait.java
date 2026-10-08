package com.craftinginterpreters.lox;

import java.util.Map;

// Chapter 13, challenge 1: a trait is a named set of methods. It is not
// callable and has no superclass; classes copy its methods in with "with".
class LoxTrait {
  final Token name;
  final Map<String, LoxFunction> methods;
  final Map<String, LoxFunction> classMethods;

  LoxTrait(Token name, Map<String, LoxFunction> methods,
           Map<String, LoxFunction> classMethods) {
    this.name = name;
    this.methods = methods;
    this.classMethods = classMethods;
  }

  @Override
  public String toString() {
    return "<trait " + name.lexeme + ">";
  }
}
