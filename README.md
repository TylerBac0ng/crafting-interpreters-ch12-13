# Crafting Interpreters — Chapters 12 & 13 Challenges

Solutions to the end-of-chapter challenges from [_Crafting Interpreters_](https://craftinginterpreters.com/) by Robert Nystrom, chapters **12 (Classes)** and **13 (Inheritance)**. This is the complete jlox tree-walk interpreter through chapter 13, so it compiles and runs on its own. It builds on my chapter 10 & 11 solutions, so anonymous functions, unused-local errors and index-based locals (and the chapter 8 & 9 REPL, uninitialized-variable error and `break`) are all still here.

## Build and run

```sh
javac -d build $(find src -name '*.java')
java -cp build com.craftinginterpreters.lox.Lox            # REPL
java -cp build com.craftinginterpreters.lox.Lox file.lox   # run a script
./run_tests.sh                                            # run the tests
```

## Chapter 12 — Classes

1. **Static methods.** `class square(n) { ... }` inside a class body is a
   static method, called as `Math.square(3)`. It uses metaclasses: `LoxClass`
   extends `LoxInstance`, and every class is an instance of its own metaclass,
   which holds the static methods. So static calls use the same property lookup
   as instance calls. Static fields (`Id.last = 0;`) and static getters come
   for free, `this` in a static method is the class, and static methods are
   inherited because a metaclass's superclass is the superclass's metaclass.
   See `tests/ch12_static.lox`.
2. **Getters.** A method with no parameter list (`area { ... }`) is a getter.
   It runs as soon as the property is read: `circle.area`. In the AST,
   `params == null` marks a getter, which is different from `()`.
   `super.area` runs the superclass's getter, and `init` can't be a getter.
   See `tests/ch12_getters.lox`.
3. **Open fields vs. encapsulation vs. access modifiers.** Written answer only
   (submitted separately).

## Chapter 13 — Inheritance

1. **Reusing code beyond single inheritance.** I picked **traits** and
   implemented them:
   `trait Swims { swim() { ... } }` and `class Duck < Animal with Swims, Flies { ... }`.
   A trait's methods are copied into the class when the class is defined, so
   method lookup doesn't change. If two traits supply different methods with
   the same name, that is an error unless the class defines the method itself.
   The same method reached through two paths (a diamond) is not a conflict.
   Traits can use other traits and can have getters and static methods. See
   `tests/ch13_traits.lox`; the reasons for choosing traits are in the written answers, submitted separately.
2. **BETA-style `inner()`.** Method lookup now starts at the **top** of the
   hierarchy, so a superclass's method wins. A subclass's version only runs when
   the superclass method calls `inner()`, which runs the next refinement down
   the chain on the same object. If there is none, `inner()` does nothing and
   returns `nil`. This also works in static methods, getters, trait methods,
   `init` and lambdas. See `tests/ch13_inner.lox`.
3. **A missing feature: lists.** You can write `[1, 2, 3]`, read and write
   elements with `xs[i]` and `xs[i] = v` (nested lists work too), and use
   `xs.length`, `push`, `pop`, `map` and `filter`. Strings can be indexed
   (read-only). Bad indexes are runtime errors, and a list that contains itself
   prints as `[...]`. See `tests/ch13_lists.lox`.

## How the chapter 12/13 features fit the chapter 11 slot environments

With index-based locals (11.4), the names the interpreter adds itself also need
fixed slots:

| Environment | Slot 0 | Slot 1 |
|---|---|---|
| Bound method (`LoxFunction.bind`) | `this` | `inner` (13.2) |
| Around a subclass's methods | `super` | — |

The resolver declares the same names in the same order. `this`, `inner` and
`super` are keywords, so they can't clash with user variables, and they are
marked as used so the unused-local check (11.3) never reports them. A local
class or trait gets its slot when the declaration finishes running. Nothing
else is defined in that environment while it runs, so the slot matches the
index the resolver gave it.

## Files changed from my chapter 10 & 11 code

| File | Change |
|---|---|
| `tool/GenerateAst.java` → `Expr.java`, `Stmt.java` | `Get`, `Set`, `This`, `Super` (ch. 12/13); `Stmt.Class` with `classMethods` (12.1) and `traits`, `Stmt.Trait` (13.1); `Inner` (13.2); `ListLiteral`, `Index`, `IndexSet` (13.3) |
| `Scanner.java`, `TokenType.java` | `[` `]` tokens; `trait`, `with`, `inner` keywords |
| `Parser.java` | Class and trait declarations, `class` methods, getters, `.` and `[]` in calls and assignments, `super`, `this`, `inner`, list literals |
| `LoxClass.java` (new) | Extends `LoxInstance` (12.1); top-down `findMethod` and `findInner` (13.2) |
| `LoxInstance.java` (new) | Fields, bound methods, getters run on access (12.2) |
| `LoxTrait.java` (new) | A trait's instance and static methods (13.1) |
| `LoxList.java` (new) | List values and their methods (13.3) |
| `LoxFunction.java` | Initializers, `bind()` with `this` and `inner` slots, getters, owning class (13.2) |
| `Resolver.java` | Class/trait/method scopes, `this`/`super`/`inner` checks, initializer `return` check |
| `Interpreter.java` | Classes with metaclasses, trait composition, property and index access, `super`, list printing |
| `AstPrinter.java` | Prints the new expression nodes |

## Tests

`./run_tests.sh` compiles the interpreter and runs every file in `tests/`,
comparing stdout and stderr with the `.expected` / `.expected_err` files. All
21 tests pass: 10 new ones for chapters 12 and 13, plus the earlier chapters'
tests unchanged. The REPL test now also defines a class and a list.
