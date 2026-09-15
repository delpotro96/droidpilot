package dev.droidpilot.planner

// A GBNF grammar handed to llama.cpp so the model cannot emit anything but a
// valid action. Malformed JSON is the first thing small models get wrong, and
// constraining the decoder removes that failure mode entirely rather than
// catching it after the fact
object ActionGrammar {

    val GBNF: String = """
root       ::= "{" ws "\"action\":" ws body ws "}"
body       ::= tap | longpress | input | swipe | back | home | wait | ask | done | fail
tap        ::= "\"tap\"" sep "\"elementId\":" ws int
longpress  ::= "\"longPress\"" sep "\"elementId\":" ws int
input      ::= "\"input\"" sep "\"elementId\":" ws int sep "\"text\":" ws string
swipe      ::= "\"swipe\"" sep "\"direction\":" ws direction ( sep "\"elementId\":" ws int )?
back       ::= "\"back\""
home       ::= "\"home\""
wait       ::= "\"wait\"" sep "\"millis\":" ws int
ask        ::= "\"ask\"" sep "\"question\":" ws string
done       ::= "\"done\"" sep "\"summary\":" ws string
fail       ::= "\"fail\"" sep "\"reason\":" ws string
direction  ::= "\"up\"" | "\"down\"" | "\"left\"" | "\"right\""
sep        ::= ws "," ws
int        ::= [0-9]+
string     ::= "\"" char* "\""
char       ::= [^"\\] | "\\" ["\\/bfnrt]
ws         ::= [ \t\n]*
""".trimIndent()

    // Mirrors the grammar for servers that take a JSON schema instead
    const val EXAMPLE = """{"action":"tap","elementId":3}"""
}
