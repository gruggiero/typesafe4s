package typesafe4s

// ============================================================================
// spec: wire-codec — Concepts Introduced: StateEncoder
// Turns a caller's own value into an Entry — total, so no given can exist for
// unrestricted Json (see Entry.fromJson for the checked path).
// ============================================================================
trait StateEncoder[A] {
  def encode(value: A): Entry
}

object StateEncoder {

  given StateEncoder[String] = new StateEncoder[String] {
    def encode(value: String): Entry = Entry.text(value)
  }

  given StateEncoder[Entry] = new StateEncoder[Entry] {
    def encode(value: Entry): Entry = value
  }

  given [V](using enc: StateEncoder[V]): StateEncoder[Map[String, V]] = new StateEncoder[Map[String, V]] {
    def encode(value: Map[String, V]): Entry =
      Entry.obj(value.toSeq.map { case (name, v) => name -> enc.encode(v) }*)
  }

  given [V](using enc: StateEncoder[V]): StateEncoder[Seq[V]] = new StateEncoder[Seq[V]] {
    def encode(value: Seq[V]): Entry = Entry.arr(value.map(enc.encode)*)
  }

  given [A](using enc: StateEncoder[A]): StateEncoder[Option[A]] = new StateEncoder[Option[A]] {
    def encode(value: Option[A]): Entry = value.fold(Entry.nothing)(enc.encode)
  }

  given [A, B](using encA: StateEncoder[A], encB: StateEncoder[B]): StateEncoder[(A, B)] = new StateEncoder[(A, B)] {
    def encode(value: (A, B)): Entry = Entry.arr(encA.encode(value._1), encB.encode(value._2))
  }

  given [A, B, C](using encA: StateEncoder[A], encB: StateEncoder[B], encC: StateEncoder[C]): StateEncoder[(A, B, C)] =
    new StateEncoder[(A, B, C)] {
      def encode(value: (A, B, C)): Entry =
        Entry.arr(encA.encode(value._1), encB.encode(value._2), encC.encode(value._3))
    }
}
