package outside

// spec: question-model — Compile-Negative Obligations
//
// This suite lives in package `outside` — NOT under `typesafe4s` — so the
// refusals it proves hold for a caller of the SDK, not just for code that can
// see its internals.
//
// Negatives on the answer-product SHAPES (a Noul certainty, an unasked name, a
// wrong-kind read) hold against the type structure itself — they are
// green-by-design and guard the surface. Negatives on the LIMITS (Score size,
// Choice size, empty set) exercise `inline if` checks inside `Score.of`,
// `Choice.of` and `QuestionSet.fromNamedTuple` — before those methods exist
// they are red (the call does not resolve; the asserted limit text cannot
// appear), which is the correct polarity for this spec.
//
// NOTE (contract footer): omission-negatives — dropping an option from a
// match on a union-typed `choice` — are E029 warnings, invisible to
// compileErrors. They are discharged by the -Werror build plus the living
// exhaustive-match witness in QuestionModelProperties ("a selected option
// can be exhausted over"), the same mechanism spec 1 recorded.
class QuestionModelCompileNegativeSuite extends munit.FunSuite {

  // spec: question-model — Compile-Negative: Reading a certainty from a Noul
  // answer — the API returns none; the probability IS the certainty
  test("a noul answer has no certainty member") {
    assert(compileErrors("""typesafe4s.NoulAnswer(0.5).confidence""").nonEmpty)
  }

  // spec: question-model — Compile-Negative: A Score with fewer than two
  // levels — refused, naming the two-level minimum
  test("a Score with fewer than two levels is refused, naming the minimum") {
    val errors = compileErrors(
      """typesafe4s.Score.of(typesafe4s.Entry.text("s?"))(Tuple1("only"))"""
    )
    assert(errors.contains("two levels"), s"the refusal does not name the two-level minimum: $errors")
  }

  // spec: question-model — Compile-Negative: A Score with more than ten
  // levels — refused, naming the ten-level maximum
  test("a Score with more than ten levels is refused, naming the maximum") {
    val errors = compileErrors(
      """typesafe4s.Score.of(typesafe4s.Entry.text("s?"))(("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"))"""
    )
    assert(errors.contains("ten levels"), s"the refusal does not name the ten-level maximum: $errors")
  }

  // spec: question-model — Compile-Negative: A Choice with more than 255
  // statically known options — refused, naming the 255-option maximum.
  // compileErrors takes a statically known string, so the 256-option tuple
  // type is spelled out literally.
  test("a Choice with more than 255 options is refused, naming the maximum") {
    val errors = compileErrors(
      """typesafe4s.Choice.of[("o1", "o2", "o3", "o4", "o5", "o6", "o7", "o8", "o9", "o10", "o11", "o12", "o13", "o14", "o15", "o16", "o17", "o18", "o19", "o20", "o21", "o22", "o23", "o24", "o25", "o26", "o27", "o28", "o29", "o30", "o31", "o32", "o33", "o34", "o35", "o36", "o37", "o38", "o39", "o40", "o41", "o42", "o43", "o44", "o45", "o46", "o47", "o48", "o49", "o50", "o51", "o52", "o53", "o54", "o55", "o56", "o57", "o58", "o59", "o60", "o61", "o62", "o63", "o64", "o65", "o66", "o67", "o68", "o69", "o70", "o71", "o72", "o73", "o74", "o75", "o76", "o77", "o78", "o79", "o80", "o81", "o82", "o83", "o84", "o85", "o86", "o87", "o88", "o89", "o90", "o91", "o92", "o93", "o94", "o95", "o96", "o97", "o98", "o99", "o100", "o101", "o102", "o103", "o104", "o105", "o106", "o107", "o108", "o109", "o110", "o111", "o112", "o113", "o114", "o115", "o116", "o117", "o118", "o119", "o120", "o121", "o122", "o123", "o124", "o125", "o126", "o127", "o128", "o129", "o130", "o131", "o132", "o133", "o134", "o135", "o136", "o137", "o138", "o139", "o140", "o141", "o142", "o143", "o144", "o145", "o146", "o147", "o148", "o149", "o150", "o151", "o152", "o153", "o154", "o155", "o156", "o157", "o158", "o159", "o160", "o161", "o162", "o163", "o164", "o165", "o166", "o167", "o168", "o169", "o170", "o171", "o172", "o173", "o174", "o175", "o176", "o177", "o178", "o179", "o180", "o181", "o182", "o183", "o184", "o185", "o186", "o187", "o188", "o189", "o190", "o191", "o192", "o193", "o194", "o195", "o196", "o197", "o198", "o199", "o200", "o201", "o202", "o203", "o204", "o205", "o206", "o207", "o208", "o209", "o210", "o211", "o212", "o213", "o214", "o215", "o216", "o217", "o218", "o219", "o220", "o221", "o222", "o223", "o224", "o225", "o226", "o227", "o228", "o229", "o230", "o231", "o232", "o233", "o234", "o235", "o236", "o237", "o238", "o239", "o240", "o241", "o242", "o243", "o244", "o245", "o246", "o247", "o248", "o249", "o250", "o251", "o252", "o253", "o254", "o255", "o256")](typesafe4s.Entry.text("c?"))"""
    )
    // "255 options" not "255": the echoed source contains `"o255"`, which
    // would satisfy a bare-digit assertion vacuously
    assert(errors.contains("255 options"), s"the refusal does not name the 255-option maximum: $errors")
  }

  // spec: question-model — Compile-Negative: A question set containing no
  // questions — an evaluation must ask at least one question
  test("a question set with nothing in it is refused") {
    assert(compileErrors("""typesafe4s.QuestionSet.fromNamedTuple(())""").nonEmpty)
  }

  // spec: question-model — Compile-Negative: Addressing an answer by a name
  // that was never asked — the answer tuple's members are exactly the asked
  // names, so a name never written is not a member
  test("a name that was never asked is not a member of the answer tuple") {
    assert(
      compileErrors("""(refundRequested = typesafe4s.NoulAnswer(0.5)).escalation""").nonEmpty
    )
  }

  // spec: question-model — Compile-Negative: Reading a Choice answer as a
  // Noul answer through the typed surface — answer shape follows the question
  test("a Choice answer cannot be read as a Noul answer") {
    assert(
      compileErrors(
        """(department = typesafe4s.ChoiceAnswer[String]("x", Map("x" -> 1.0), 0.5)).department: typesafe4s.NoulAnswer"""
      ).nonEmpty
    )
  }
}
