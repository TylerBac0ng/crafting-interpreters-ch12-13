package com.craftinginterpreters.lox;

import java.util.ArrayList;
import java.util.List;

// Chapter 13, challenge 3: a growable list. "[1, 2]" creates one,
// "list[i]" and "list[i] = x" read and write elements, and a few methods
// are reached with ".", like on an instance.
class LoxList {
  final List<Object> elements;

  LoxList(List<Object> elements) {
    this.elements = elements;
  }

  Object get(int index, Token bracket) {
    return elements.get(checkIndex(index, bracket));
  }

  void set(int index, Object value, Token bracket) {
    elements.set(checkIndex(index, bracket), value);
  }

  private int checkIndex(int index, Token bracket) {
    if (index < 0 || index >= elements.size()) {
      throw new RuntimeError(bracket, "List index " + index +
          " out of range for length " + elements.size() + ".");
    }
    return index;
  }

  Object getProperty(Token name) {
    switch (name.lexeme) {
      case "length":
        return (double)elements.size();

      case "push":
        return new Method("push", 1) {
          @Override
          Object run(Interpreter interpreter, List<Object> arguments) {
            elements.add(arguments.get(0));
            return null;
          }
        };

      case "pop":
        return new Method("pop", 0) {
          @Override
          Object run(Interpreter interpreter, List<Object> arguments) {
            if (elements.isEmpty()) {
              throw new RuntimeError(name, "Can't pop an empty list.");
            }
            return elements.remove(elements.size() - 1);
          }
        };

      case "map":
        return new Method("map", 1) {
          @Override
          Object run(Interpreter interpreter, List<Object> arguments) {
            LoxCallable fn = callback(name, arguments.get(0));
            List<Object> result = new ArrayList<>();
            for (Object element : new ArrayList<>(elements)) {
              result.add(callWith(fn, interpreter, element));
            }
            return new LoxList(result);
          }
        };

      case "filter":
        return new Method("filter", 1) {
          @Override
          Object run(Interpreter interpreter, List<Object> arguments) {
            LoxCallable fn = callback(name, arguments.get(0));
            List<Object> result = new ArrayList<>();
            for (Object element : new ArrayList<>(elements)) {
              if (Interpreter.isTruthy(callWith(fn, interpreter, element))) {
                result.add(element);
              }
            }
            return new LoxList(result);
          }
        };
    }

    throw new RuntimeError(name,
        "Lists have no property '" + name.lexeme + "'.");
  }

  private static LoxCallable callback(Token name, Object value) {
    if (!(value instanceof LoxCallable) ||
        ((LoxCallable)value).arity() != 1) {
      throw new RuntimeError(name, "Argument to '" + name.lexeme +
          "' must be a function that takes one argument.");
    }
    return (LoxCallable)value;
  }

  private static Object callWith(LoxCallable fn, Interpreter interpreter,
                                 Object argument) {
    List<Object> arguments = new ArrayList<>();
    arguments.add(argument);
    return fn.call(interpreter, arguments);
  }

  private abstract static class Method implements LoxCallable {
    private final String name;
    private final int arity;

    Method(String name, int arity) {
      this.name = name;
      this.arity = arity;
    }

    abstract Object run(Interpreter interpreter, List<Object> arguments);

    @Override
    public int arity() { return arity; }

    @Override
    public Object call(Interpreter interpreter, List<Object> arguments) {
      return run(interpreter, arguments);
    }

    @Override
    public String toString() { return "<native fn " + name + ">"; }
  }
}
