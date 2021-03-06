/*
 * Copyright 2014–2017 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar

import slamdata.Predef.{Set => _, _}
import quasar.DataArbitrary._
import quasar.Type._

import org.scalacheck._, Gen._

trait TypeArbitrary {
  implicit def arbitraryType: Arbitrary[Type] = Arbitrary { Gen.sized(depth => typeGen(depth/25)) }

  def arbitrarySimpleType = Arbitrary { Gen.sized(depth => complexGen(depth/25, simpleGen)) }

  def arbitraryTerminal = Arbitrary { terminalGen }

  def arbitraryConst = Arbitrary { constGen }

  def arbitraryNonnestedType = Arbitrary { Gen.oneOf(Gen.const(Top), Gen.const(Bottom), simpleGen) }

  def arbitrarySubtype(superType: Type) = Arbitrary {
    Arbitrary.arbitrary[Type].suchThat(superType.contains(_))
  }

  /** `arbitrarySubtype` is more general, but throws away too many cases to
    * succeed. This version uses `suchThat` in a much more restricted context.
    */
  val arbitraryNumeric = Arbitrary {
    Gen.oneOf(
      Gen.const(Type.Dec),
      Gen.const(Type.Int),
      constGen.suchThat(Type.Numeric.contains(_)))
  }

  def typeGen(depth: Int): Gen[Type] = {
    // NB: never nests Top or Bottom inside any complex type, because that's mostly nonsensical.
    val gens = Gen.oneOf(Top, Bottom) :: List(terminalGen, constGen, objectGen, arrayGen).map(complexGen(depth, _))

    Gen.oneOf(gens(0), gens(1), gens.drop(2): _*)
  }

  def complexGen(depth: Int, gen: Gen[Type]): Gen[Type] =
    if (depth > 1) coproductGen(depth, gen)
    else gen

  def coproductGen(depth: Int, gen: Gen[Type]): Gen[Type] = for {
    left <- complexGen(depth-1, gen)
    right <- complexGen(depth-1, gen)
  } yield left ⨿ right

  def simpleGen: Gen[Type] = Gen.oneOf(terminalGen, simpleConstGen)

  def terminalGen: Gen[Type] = Gen.oneOf(Null, Str, Type.Int, Dec, Bool, Binary, Timestamp, Date, Time, Interval)

  def simpleConstGen: Gen[Type] = DataArbitrary.simpleData.map(Const(_))
  def constGen: Gen[Type] = Arbitrary.arbitrary[Data].map(Const(_))

  def fieldGen: Gen[(String, Type)] = for {
    c <- Gen.alphaChar
    t <- Gen.oneOf(terminalGen, constGen)
  } yield (c.toString(), t)

  def objectGen: Gen[Type] = for {
    t <- Gen.listOf(fieldGen)
    u <- Gen.oneOf[Option[Type]](None, Gen.oneOf(terminalGen, constGen).map(Some(_)))
  } yield Obj(t.toMap, u)

  def arrGen: Gen[Type] = for {
    t <- Gen.listOf(Gen.oneOf(terminalGen, constGen))
  } yield Arr(t)

  def flexArrayGen: Gen[Type] = for {
    i <- Gen.chooseNum(0, 10)
    n <- Gen.oneOf[Option[Int]](None, Gen.chooseNum(i, 20).map(Some(_)))
    t <- Gen.oneOf(terminalGen, constGen)
  } yield FlexArr(i, n, t)

  def arrayGen: Gen[Type] = Gen.oneOf(arrGen, flexArrayGen)
}

object TypeArbitrary extends TypeArbitrary
