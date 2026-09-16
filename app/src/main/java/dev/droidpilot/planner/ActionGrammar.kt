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
// action that could act on it.
//
// wait is not offered. It was the answer a small model reached for whenever it
// could not decide, and a screen that does not move because nothing was pressed
// looks exactly like a screen that is loading, so it kept choosing it. The loop
// already pauses between steps; a planner that genuinely cannot tell what to do
// has ask, which reaches the person who can
object ActionGrammar {

    val GBNF: String = """
root       ::= "{" ws "\"action\":" ws body ws "}"
body       ::= tap | longpress | tapat | longpressat | input | swipe | launch | back | home | ask | done | fail
launch     ::= "\"launch\"" sep "\"package\":" ws string
tap        ::= "\"tap\"" sep "\"elementId\":" ws int sep "\"risk\":" ws risk
longpress  ::= "\"longPress\"" sep "\"elementId\":" ws int sep "\"risk\":" ws risk
tapat      ::= "\"tapAt\"" sep "\"x\":" ws int sep "\"y\":" ws int sep "\"risk\":" ws risk
longpressat ::= "\"longPressAt\"" sep "\"x\":" ws int sep "\"y\":" ws int sep "\"risk\":" ws risk
input      ::= "\"input\"" sep "\"elementId\":" ws int sep "\"text\":" ws string
swipe      ::= "\"swipe\"" sep "\"direction\":" ws direction ( sep "\"elementId\":" ws int )? sep "\"risk\":" ws risk
back       ::= "\"back\""
home       ::= "\"home\""
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

    // Nothing else moved the model as much as this line. Shown a tap here it
    // tapped, and on a screen with nothing worth tapping it swiped instead;
    // shown a launch it launched. The example has to match the decision being
    // asked for, so there is one for each
    const val EXAMPLE = """{"action":"tap","elementId":3,"risk":"none"}"""

    const val EXAMPLE_LAUNCH = """{"action":"launch","package":"com.example.app"}"""

    // Our own screen has exactly one sensible move. Offering the rest of the
    // vocabulary there invited the planner to press our own buttons, and the
    // prompt alone did not stop it
    val GBNF_LEAVE_ONLY: String = GBNF.replace(
        "body       ::= tap | longpress | tapat | longpressat | input | swipe | launch | back | home | ask | done | fail",
        "body       ::= launch | ask | fail"
    )
}
