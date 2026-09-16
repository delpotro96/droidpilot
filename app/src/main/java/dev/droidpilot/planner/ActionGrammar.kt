package dev.droidpilot.planner

// A GBNF grammar handed to llama.cpp so the model cannot emit anything but a
// valid action. Malformed JSON is the first thing small models get wrong, and
// constraining the decoder removes that failure mode entirely rather than
// catching it after the fact.
//
// Every press carries a declared risk. Four rounds of trying to infer danger
// from the label text failed in both directions at once - a chat message about
// having paid locked the conversation while Confirm and pay went through - and
// the reason is that the string does not carry the information. The model
// knows it is pressing a delete button; requiring it to say so is far more
// reliable than matching the word. The keyword rules stay, but only to
// escalate what the model played down.
//
// tapAt and longPressAt exist because a game renders its whole interface into
// a single surface. There is nothing to number, so the element actions cannot
// reach it at all; the screenshot the planner is shown in that case had no
// action that could act on it
object ActionGrammar {

    val GBNF: String = """
root       ::= "{" ws "\"action\":" ws body ws "}"
body       ::= tap | longpress | tapat | longpressat | input | swipe | launch | back | home | wait | ask | done | fail
launch     ::= "\"launch\"" sep "\"package\":" ws string
tap        ::= "\"tap\"" sep "\"elementId\":" ws int sep "\"risk\":" ws risk
longpress  ::= "\"longPress\"" sep "\"elementId\":" ws int sep "\"risk\":" ws risk
tapat      ::= "\"tapAt\"" sep "\"x\":" ws int sep "\"y\":" ws int sep "\"risk\":" ws risk
longpressat ::= "\"longPressAt\"" sep "\"x\":" ws int sep "\"y\":" ws int sep "\"risk\":" ws risk
input      ::= "\"input\"" sep "\"elementId\":" ws int sep "\"text\":" ws string
swipe      ::= "\"swipe\"" sep "\"direction\":" ws direction ( sep "\"elementId\":" ws int )? sep "\"risk\":" ws risk
back       ::= "\"back\""
home       ::= "\"home\""
wait       ::= "\"wait\"" sep "\"millis\":" ws int
ask        ::= "\"ask\"" sep "\"question\":" ws string
done       ::= "\"done\"" sep "\"summary\":" ws string
fail       ::= "\"fail\"" sep "\"reason\":" ws string
direction  ::= "\"up\"" | "\"down\"" | "\"left\"" | "\"right\""
risk       ::= "\"none\"" | "\"spends\"" | "\"irreversible\""
sep        ::= ws "," ws
int        ::= [0-9]+
string     ::= "\"" char* "\""
char       ::= [^"\\] | "\\" ["\\/bfnrt]
ws         ::= [ \t\n]*
""".trimIndent()

    // Mirrors the grammar for servers that take a JSON schema instead
    const val EXAMPLE = """{"action":"tap","elementId":3,"risk":"none"}"""
}
