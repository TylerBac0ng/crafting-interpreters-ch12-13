package com.craftinginterpreters.lox;

enum TokenType {
  // Single-character tokens.
  LEFT_PAREN, RIGHT_PAREN, LEFT_BRACE, RIGHT_BRACE,
  LEFT_BRACKET, RIGHT_BRACKET, // Chapter 13, challenge 3.
  COMMA, DOT, MINUS, PLUS, SEMICOLON, SLASH, STAR,

  // One or two character tokens.
  BANG, BANG_EQUAL,
  EQUAL, EQUAL_EQUAL,
  GREATER, GREATER_EQUAL,
  LESS, LESS_EQUAL,

  // Literals.
  IDENTIFIER, STRING, NUMBER,

  // Keywords.
  AND, BREAK, CLASS, ELSE, FALSE, FUN, FOR, IF, INNER, NIL, OR,
  PRINT, RETURN, SUPER, THIS, TRAIT, TRUE, VAR, WHILE, WITH,

  EOF
}
