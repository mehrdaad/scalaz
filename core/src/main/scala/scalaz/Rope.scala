package scalaz

import collection.IndexedSeqLike
import collection.immutable.IndexedSeq
import collection.generic.CanBuildFrom
import reflect.ClassTag

import syntax.Ops
import scalaz.{ImmutableArray => IA}
import std.anyVal._
import FingerTree._

/**
  * Ropes or 'heavyweight Strings' are an alternative to Strings.
  * In essence they are binary trees whose leaves are arrays of characters.
  * Their advantage over ordinary strings is support for efficient concatenation and substring operations,
  * which scale to long strings.
  *
  * They were first described in the paper: Ropes: an Alternative to Strings.
  * by Hans-J. Boehm , Russ Atkinson , Michael Plass
  *
  * @see [[http://citeseer.ist.psu.edu/viewdoc/download?doi=10.1.1.14.9450&rep=rep1&type=pdf]]
  */
@deprecated("Rope is deprecated. Use `Cord` instead", "7.1")
sealed class Rope[A : ClassTag](val self: Rope.FingerTreeIntPlus[ImmutableArray[A]])
    extends Ops[Rope.FingerTreeIntPlus[ImmutableArray[A]]] {
  import Rope._
  implicit def sizer = UnitReducer((arr: ImmutableArray[A]) => arr.length)

  def length: Int = self.measure

  def get(i: Int): Option[A] = {
    val (right, left) = self.split(_ > i)
    left.viewl.headOption.flatMap(_.lift(i - right.measure))
  }

  /**Concatenates two Ropes. `(O lg min(r1, r2))` where `r1` and `r2` are their sizes. */
  def ++(xs: Rope[A]): Rope[A] = Rope(self <++> xs.self)

  /**Appends the given chunk to the rope*/
  def ::+(chunk: ImmutableArray[A]): Rope[A] =
    if (chunk.isEmpty)
      this
    else
      Rope(
        self.viewr.fold(
          single(chunk)(sizer),
          (_, last) => {
          if (last.length + chunk.length <= baseChunkLength)
            self :-| (last ++ chunk)
          else
            self :+ chunk
          }
        )
      )

  /**Prepends the given chunk to this rope*/
  def +::(chunk: ImmutableArray[A]): Rope[A] =
    if (chunk.isEmpty)
      this
    else
      Rope(
        self.viewl.fold(
          single(chunk)(sizer),
          (head, _) =>
          if (chunk.length + head.length <= baseChunkLength)
            (chunk ++ head) |-: self
          else
            chunk +: self
        )
      )

  /**Appends the given element to this rope*/
  def :+(x: A): Rope[A] = this ::+ IA.fromArray(Array(x))

  /**Prepends the given element to this rope*/
  def +:(x: A): Rope[A] = IA.fromArray(Array(x)) +:: this

  /**tail of the Rope*/
  @annotation.tailrec
  final def tail: Rope[A] = {
    val head = self.head
    if(head.length > 1)
      Rope(head.tail +: self.tail)
    else if(head.length == 1) Rope(self.tail)
    else Rope(self.tail).tail
  }

  /**first element of the rope*/
  @annotation.tailrec
  final def init: Rope[A] = {
    val last = self.last
    if(last.length > 1)
      Rope(self.init :+ last.init)
    else if(last.length == 1) Rope(self.init)
    else Rope(self.init).init
  }
//      def map[B](f: A => B) = Rope(value map f) TODO
//      def flatMap[B](f: A => Rope[B]) =
//        Rope(value.foldl(empty[Int, B])((ys, x) => ys <++> f(x).value))

  // override def foreach[U](f: A => U): Unit = value.foreach(_.foreach(f))

  def iterator: Iterator[A] = self.iterator.flatMap(_.iterator)
  def reverseIterator: Iterator[A] = self.reverseIterator.flatMap(_.reverseIterator)

  // TODO override def reverse

  def chunks: Stream[ImmutableArray[A]] = self.toStream
}
    
@deprecated("Rope is deprecated. Use `Cord` instead", "7.1")
sealed class WrappedRope[A : ClassTag](val self: Rope[A])
    extends Ops[Rope[A]] with IndexedSeq[A] with IndexedSeqLike[A, WrappedRope[A]] {
  import Rope._

  def apply(i: Int): A = self.get(i).get

  def ++(xs: WrappedRope[A]) = wrapRope(self ++ xs.self)

  // override def :+(x: A) = wrapRope(value :+ x)
  // override def +:(x: A) = wrapRope(x +: value)
  override def tail = self.tail
  override def init = self.init
//def map[B](f: A => B) = Rope(value map f)
//def flatMap[B](f: A => Rope[B]) =
//  Rope(value.foldl(empty[Int, B])((ys, x) => ys <++> f(x).value))

//override def foreach[U](f: A => U): Unit = value.foreach(_.foreach(f))

  override def iterator: Iterator[A] = self.self.iterator.flatMap(_.iterator)

  override def reverseIterator: Iterator[A] = self.self.reverseIterator.flatMap(_.reverseIterator)

  // TODO override def reverse

  override def toStream = self.chunks.flatten

  override def length = self.length

  protected[this] override def newBuilder = new RopeBuilder[A].mapResult(wrapRope(_))
}


@deprecated("Rope is deprecated. Use `Cord` instead", "7.1")
sealed class RopeCharW(val self: Rope[Char]) extends Ops[Rope[Char]] {
  def asString = {
    val stringBuilder = new StringBuilder(self.length)
    appendTo(stringBuilder)
    stringBuilder.toString
  }

  def appendTo(stringBuilder: StringBuilder) {
    self.chunks.foreach(ia => stringBuilder.append(ia.asString))
  }
}


import collection.mutable.Builder
import scalaz.{ImmutableArray => IA}

@deprecated("Rope is deprecated. Use `Cord` instead", "7.1")
final class RopeBuilder[A : ClassTag] extends Builder[A, Rope[A]] {
  import Rope._
  private var startRope: Rope[A] = Rope.empty[A]
  private var tailBuilder: Builder[A, ImmutableArray[A]] = IA.newBuilder[A]
  private var tailLength = 0

  def clear() {
    startRope = Rope.empty[A]
    tailBuilder = IA.newBuilder[A]
    tailLength = 0
  }

  def +=(elem: A) = {
    if (tailLength < baseChunkLength) {
      tailBuilder += elem
      tailLength += 1
    }
    else {
      cleanTail
      tailBuilder += elem
      tailLength = 1
    }
    this
  }

  def result = startRope ::+ tailBuilder.result

  /*override def sizeHint(size: Int) {
    tailBuilder.sizeHint(math.min(size - startRope.length, baseChunkLength))
  }*/

      // TODO fix and reinstate
//      import collection.mutable.ArrayLike
//      override def ++=(xs: TraversableOnce[A]) = {
//        xs match {
//          case xs: Rope[A] => {
//            cleanTail
//            startRope ++= xs
//          }
//          case xs: ImmutableArray[A] => {
//            cleanTail
//            startRope ::+= xs
//          }
//          case xs: ArrayLike[A, _] => {
//            cleanTail
//            tailBuilder ++= xs
//          }
//          case _ =>  super.++=(xs)
//        }
//        this
//      }
//    }

  private def cleanTail {
    startRope ::+= tailBuilder.result
    tailBuilder.clear()
  }
}

@deprecated("Rope is deprecated. Use `Cord` instead", "7.1")
object Rope {
  type FingerTreeIntPlus[A] = FingerTree[Int, A]

  implicit def wrapRope[A : ClassTag](rope: Rope[A]): WrappedRope[A] = new WrappedRope(rope)
  implicit def unwrapRope[A](wrappedRope: WrappedRope[A]): Rope[A] = wrappedRope.self
  implicit def wrapRopeChar(rope: Rope[Char]): RopeCharW = new RopeCharW(rope)
  implicit def sizer[A]: Reducer[ImmutableArray[A], Int] = UnitReducer(_.length)

  val baseChunkLength = 16

  def apply[A : ClassTag](v: FingerTreeIntPlus[ImmutableArray[A]]): Rope[A] = new Rope[A](v)

  def empty[A : ClassTag] = Rope(FingerTree.empty[Int, ImmutableArray[A]])

  def fromArray[A : ClassTag](a: Array[A]): Rope[A] =
    if (a.isEmpty) empty[A] else Rope(single(IA.fromArray(a)))

  def fromString(str: String): Rope[Char] =
    if (str.isEmpty) empty[Char] else Rope(single(IA.fromString(str)))

  def fromChunks[A : ClassTag](chunks: Seq[ImmutableArray[A]]): Rope[A] =
    Rope(chunks.foldLeft(FingerTree.empty[Int, ImmutableArray[A]])((tree, chunk) => if (!chunk.isEmpty) tree :+ chunk else tree))
//      def apply[A](as: A*) = fromSeq(as)
//      def fromSeq[A](as: Seq[A]) = Rope(as.foldLeft(empty[Int, A](Reducer(a => 1)))((x, y) => x :+ y))

  def newBuilder[A : ClassTag]: Builder[A, Rope[A]] = new RopeBuilder[A]

  implicit def canBuildFrom[T : ClassTag]: CanBuildFrom[Rope[_], T, Rope[T]] =
    new CanBuildFrom[Rope[_], T, Rope[T]] {
      def apply(from: Rope[_]): Builder[T, Rope[T]] = newBuilder[T]
      def apply(): Builder[T, Rope[T]] = newBuilder[T]
    }

  implicit val ropeInstance: Foldable[Rope] with Plus[Rope] =
    new Foldable[Rope] with Plus[Rope] {
      override def length[A](fa: Rope[A]) =
        fa.length
      override def index[A](fa: Rope[A], i: Int) =
        fa.get(i)
      override def foldLeft[A, B](fa: Rope[A], z: B)(f: (B, A) => B) =
        Foldable[FingerTreeIntPlus].foldLeft(fa.self, z)((b, a) => Foldable[ImmutableArray].foldLeft(a, b)(f))
      def foldMap[A, B: Monoid](fa: Rope[A])(f: A => B) =
        Foldable[FingerTreeIntPlus].foldMap(fa.self)(Foldable[ImmutableArray].foldMap(_)(f))
      def foldRight[A, B](fa: Rope[A], z: => B)(f: (A, => B) => B) =
        Foldable[FingerTreeIntPlus].foldRight(fa.self, z)(Foldable[ImmutableArray].foldRight(_, _)(f))
      def plus[A](a: Rope[A], b: => Rope[A]) =
        a ++ b
    }

  import std.list._
  implicit def ropeEqual[A: Equal]: Equal[Rope[A]] =
    Equal.equalBy(_.self.toList.flatten)
}
