/*
 * Copyright 2014–2016 SlamData Inc.
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

package quasar.sql

import quasar.Predef._
import quasar.Data
import quasar.common.SortDir
import quasar.std._, StdLib._, agg._, array._, date._, identity._, math._

import matryoshka.Fix
import scalaz.NonEmptyList
import scalaz.syntax.nel._

class CompilerSpec extends quasar.Qspec with CompilerHelpers {
  // NB: imports are here to shadow duplicated names in [[quasar.sql]]. We
  //     need to do a better job of handling this.
  import quasar.std.StdLib._, relations._, StdLib.set._, string._, structural._
  import quasar.frontend.fixpoint.lpf

  "compiler" should {
    "compile simple constant example 1" in {
      testLogicalPlanCompile(
        "select 1",
        makeObj("0" -> lpf.constant(Data.Int(1))))
    }

    "compile simple boolean literal (true)" in {
      testLogicalPlanCompile(
        "select true",
        makeObj("0" -> lpf.constant(Data.Bool(true))))
    }

    "compile simple boolean literal (false)" in {
      testLogicalPlanCompile(
        "select false",
        makeObj("0" -> lpf.constant(Data.Bool(false))))
    }

    "compile simple constant with multiple named projections" in {
      testLogicalPlanCompile(
        "select 1.0 as a, \"abc\" as b",
        makeObj(
          "a" -> lpf.constant(Data.Dec(1.0)),
          "b" -> lpf.constant(Data.Str("abc"))))
    }

    "compile complex constant" in {
      testTypedLogicalPlanCompile("[1, 2, 3, 4, 5][*] limit 3 offset 1",
        lpf.constant(Data.Set(List(Data.Int(2), Data.Int(3)))))
    }

    "select complex constant" in {
      testTypedLogicalPlanCompile(
        "select {\"a\": 1, \"b\": 2, \"c\": 3, \"d\": 4, \"e\": 5}{*} limit 3 offset 1",
        lpf.constant(Data.Set(List(
          Data.Obj(ListMap("0" -> Data.Int(2))),
          Data.Obj(ListMap("0" -> Data.Int(3)))))))
    }

    "select complex constant 2" in {
      testTypedLogicalPlanCompile(
        "select {\"a\": 1, \"b\": 2, \"c\": 3, \"d\": 4, \"e\": 5}{*:} limit 3 offset 1",
        lpf.constant(Data.Set(List(
          Data.Obj(ListMap("0" -> Data.Str("b"))),
          Data.Obj(ListMap("0" -> Data.Str("c")))))))
    }

    "compile expression with timestamp, date, time, and interval" in {
      import java.time.{Instant, LocalDate, LocalTime}

      testTypedLogicalPlanCompile(
        """select timestamp("2014-11-17T22:00:00Z") + interval("PT43M40S"), date("2015-01-19"), time("14:21")""",
        lpf.constant(Data.Obj(ListMap(
          "0" -> Data.Timestamp(Instant.parse("2014-11-17T22:43:40Z")),
          "1" -> Data.Date(LocalDate.parse("2015-01-19")),
          "2" -> Data.Time(LocalTime.parse("14:21:00.000"))))))
    }

    "compile simple constant from collection" in {
      testTypedLogicalPlanCompile("select 1 from zips",
        lpf.constant(Data.Obj(ListMap("0" -> Data.Int(1)))))
    }

    "compile select substring" in {
      testLogicalPlanCompile(
        "select substring(bar, 2, 3) from foo",
        Squash(
          makeObj(
            "0" ->
              Substring[FLP](
                ObjectProject(read("foo"), lpf.constant(Data.Str("bar"))),
                lpf.constant(Data.Int(2)),
                lpf.constant(Data.Int(3))))))
    }

    "compile select length" in {
      testLogicalPlanCompile(
        "select length(bar) from foo",
        Squash(
          makeObj(
            "0" -> Length[FLP](ObjectProject(read("foo"), lpf.constant(Data.Str("bar")))))))
    }

    "compile simple select *" in {
      testLogicalPlanCompile("select * from foo", Squash(read("foo")))
    }

    "compile qualified select *" in {
      testLogicalPlanCompile("select foo.* from foo", Squash(read("foo")))
    }

    "compile qualified select * with additional fields" in {
      testLogicalPlanCompile(
        "select foo.*, bar.address from foo, bar",
        lpf.let('__tmp0,
          InnerJoin(read("foo"), read("bar"), lpf.constant(Data.Bool(true))),
          Squash[FLP](
            ObjectConcat[FLP](
              ObjectProject(lpf.free('__tmp0), JoinDir.Left.const[Fix]),
              makeObj(
                "address" ->
                  ObjectProject[FLP](
                    ObjectProject(lpf.free('__tmp0), JoinDir.Right.const[Fix]),
                    lpf.constant(Data.Str("address"))))))))
    }

    "compile deeply-nested qualified select *" in {
      testLogicalPlanCompile(
        "select foo.bar.baz.*, bar.address from foo, bar",
        lpf.let('__tmp0,
          InnerJoin(read("foo"), read("bar"), lpf.constant(Data.Bool(true))),
          Squash[FLP](
            ObjectConcat[FLP](
              ObjectProject[FLP](
                ObjectProject[FLP](
                  ObjectProject(lpf.free('__tmp0), JoinDir.Left.const[Fix]),
                  lpf.constant(Data.Str("bar"))),
                lpf.constant(Data.Str("baz"))),
              makeObj(
                "address" ->
                  ObjectProject[FLP](
                    ObjectProject(lpf.free('__tmp0), JoinDir.Right.const[Fix]),
                    lpf.constant(Data.Str("address"))))))))
    }

    "compile simple select with unnamed projection which is just an identifier" in {
      testLogicalPlanCompile(
        "select name from city",
        Squash(
          makeObj(
            "name" -> ObjectProject(read("city"), lpf.constant(Data.Str("name"))))))
    }

    "compile basic let" in {
      testLogicalPlanCompile(
        "foo := 5; foo",
        lpf.constant(Data.Int(5)))
    }

    "compile basic let, ignoring the form" in {
      testLogicalPlanCompile(
        "bar := 5; 7",
        lpf.constant(Data.Int(7)))
    }

    "compile nested lets" in {
      testLogicalPlanCompile(
        """foo := 5; bar := 7; bar + foo""",
        Add[FLP](lpf.constant(Data.Int(7)), lpf.constant(Data.Int(5))))
    }

    "compile let with select in body from let binding ident" in {
      val query = """foo := (1,2,3); select * from foo"""
      val expectation =
        Squash[FLP](
          ShiftArray[FLP](
            ArrayConcat[FLP](
              ArrayConcat[FLP](
                MakeArrayN[Fix](lpf.constant(Data.Int(1))),
                MakeArrayN[Fix](lpf.constant(Data.Int(2)))),
              MakeArrayN[Fix](lpf.constant(Data.Int(3))))))

      testLogicalPlanCompile(query, expectation)
    }

    "compile let with select in body selecting let binding ident" in {
      val query = """foo := 12; select foo from bar"""
      val expectation =
        Squash(
          makeObj(
            "0" ->
              lpf.constant(Data.Int(12))))

      testLogicalPlanCompile(query, expectation)
    }

    "fail to compile let inside select with ambigious reference" in {
      compile("""select foo from (bar := 12; baz) as quag""") must
        beLeftDisjunction  // AmbiguousReference(baz)
    }

    "compile let inside select with table reference" in {
      val query = """select foo from (bar := 12; select * from baz) as quag"""
      val expectation =
        Squash(
          makeObj(
            "foo" ->
              ObjectProject[FLP](Squash(read("baz")), lpf.constant(Data.Str("foo")))))

      testLogicalPlanCompile(query, expectation)
    }

    "compile let inside select with ident reference" in {
      val query = """select foo from (bar := 12; select * from bar) as quag"""
      val expectation =
        Squash(
          makeObj(
            "foo" ->
              ObjectProject[FLP](Squash(lpf.constant(Data.Int(12))), lpf.constant(Data.Str("foo")))))

      testLogicalPlanCompile(query, expectation)
    }

    "compile selection with same ident as nested let" in {
      val query = """select bar from (bar := 12; select * from bar) as quag"""
      val expectation =
        Squash(
          makeObj(
            "bar" ->
              ObjectProject[FLP](Squash(lpf.constant(Data.Int(12))), lpf.constant(Data.Str("bar")))))

      testLogicalPlanCompile(query, expectation)
    }

    "compile selection with same ident as nested let and alias" in {
      val query = """select bar from (bar := 12; select * from bar) as bar"""
      val expectation =
        Squash(
          makeObj(
            "0" ->
              Squash(lpf.constant(Data.Int(12)))))

      testLogicalPlanCompile(query, expectation)
    }

    "compile let with select in form and body" in {
      val query = """foo := select * from bar; select * from foo"""
      val expectation = Squash[FLP](Squash[FLP](read("bar")))

      testLogicalPlanCompile(query, expectation)
    }

    "compile let with inner context that shares a table reference" in {
      val query = """select (foo := select * from bar; select * from foo) from foo"""
      val expectation =
        Squash[FLP](
          makeObj(
            "0" ->
              Squash[FLP](Squash[FLP](read("bar")))))

      testLogicalPlanCompile(query, expectation)
    }

    "compile let with an inner context of as that shares a binding name" in {
      val query = """foo := 4; select * from bar as foo"""
      val expectation = Squash(read("bar"))

      testLogicalPlanCompile(query, expectation)
    }

    "fail to compile let with an inner context of let that shares a binding name in expression context" in {
      val query = """foo := 4; select * from (foo := bar; foo) as quag"""

      compile(query) must beLeftDisjunction // ambiguous reference for `bar` - `4` or `foo`
    }

    "compile let with an inner context of as that shares a binding name in table context" in {
      val query = """foo := 4; select * from (foo := select * from bar; foo) as quag"""
      val expectation = Squash[FLP](Squash[FLP](read("bar")))

      testLogicalPlanCompile(query, expectation)
    }

    "compile simple 1-table projection when root identifier is also a projection" in {
      // 'foo' must be interpreted as a projection because only this interpretation is possible
      testLogicalPlanCompile(
        "select foo.bar from baz",
        Squash(
          makeObj(
            "bar" ->
              ObjectProject[FLP](
                ObjectProject(read("baz"), lpf.constant(Data.Str("foo"))),
                lpf.constant(Data.Str("bar"))))))
    }

    "compile simple 1-table projection when root identifier is also a table ref" in {
      // 'foo' must be interpreted as a table reference because this
      // interpretation is possible and consistent with ANSI SQL.
      testLogicalPlanCompile(
        "select foo.bar from foo",
        Squash(
          makeObj(
            "bar" -> ObjectProject(read("foo"), lpf.constant(Data.Str("bar"))))))
    }

    "compile two term addition from one table" in {
      testLogicalPlanCompile(
        "select foo + bar from baz",
        lpf.let('__tmp0, read("baz"),
          Squash(
            makeObj(
              "0" ->
                Add[FLP](
                  ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("foo"))),
                  ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("bar"))))))))
    }

    "compile negate" in {
      testLogicalPlanCompile(
        "select -foo from bar",
        Squash(
          makeObj(
            "0" ->
              Negate[FLP](ObjectProject(read("bar"), lpf.constant(Data.Str("foo")))))))
    }

    "compile modulo" in {
      testLogicalPlanCompile(
        "select foo % baz from bar",
        lpf.let('__tmp0, read("bar"),
          Squash(
            makeObj(
              "0" ->
                Modulo[FLP](
                  ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("foo"))),
                  ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("baz"))))))))
    }

    "compile coalesce" in {
      testLogicalPlanCompile(
        "select coalesce(bar, baz) from foo",
        lpf.let('__tmp0, read("foo"),
          lpf.let('__tmp1, ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
            Squash(
              makeObj(
                "0" ->
                  Cond[FLP](
                    Eq[FLP](lpf.free('__tmp1), lpf.constant(Data.Null)),
                    ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("baz"))),
                    lpf.free('__tmp1)))))))
    }

    "compile date field extraction" in {
      testLogicalPlanCompile(
        "select date_part(\"day\", baz) from foo",
        Squash(
          makeObj(
            "0" ->
              ExtractDayOfMonth[FLP](
                ObjectProject(read("foo"), lpf.constant(Data.Str("baz")))))))
    }

    "compile conditional" in {
      testLogicalPlanCompile(
        "select case when pop < 10000 then city else loc end from zips",
        lpf.let('__tmp0, read("zips"),
          Squash(makeObj(
            "0" ->
              Cond[FLP](
                Lt[FLP](
                  ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("pop"))),
                  lpf.constant(Data.Int(10000))),
                ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("city"))),
                ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("loc"))))))))
    }

    "compile conditional (match) without else" in {
      testLogicalPlanCompile(
                   "select case when pop = 0 then \"nobody\" end from zips",
        compileExp("select case when pop = 0 then \"nobody\" else null end from zips"))
    }

    "compile conditional (switch) without else" in {
      testLogicalPlanCompile(
                   "select case pop when 0 then \"nobody\" end from zips",
        compileExp("select case pop when 0 then \"nobody\" else null end from zips"))
    }

    "have ~~ as alias for LIKE" in {
      testLogicalPlanCompile(
                   "select pop from zips where city ~~ \"%BOU%\"",
        compileExp("select pop from zips where city LIKE \"%BOU%\""))
    }

    "have !~~ as alias for NOT LIKE" in {
      testLogicalPlanCompile(
                   "select pop from zips where city !~~ \"%BOU%\"",
        compileExp("select pop from zips where city NOT LIKE \"%BOU%\""))
    }

    "compile array length" in {
      testLogicalPlanCompile(
        "select array_length(bar, 1) from foo",
        Squash(
          makeObj(
            "0" ->
              ArrayLength[FLP](
                ObjectProject(read("foo"), lpf.constant(Data.Str("bar"))),
                lpf.constant(Data.Int(1))))))
    }

    "compile concat" in {
      testLogicalPlanCompile(
        "select concat(foo, concat(\" \", bar)) from baz",
        lpf.let('__tmp0, read("baz"),
          Squash(
            makeObj(
              "0" ->
                Concat[FLP](
                  ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("foo"))),
                  Concat[FLP](
                    lpf.constant(Data.Str(" ")),
                    ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("bar")))))))))
    }

    "filter on constant false" in {
      testTypedLogicalPlanCompile("select * from zips where false",
        lpf.constant(Data.Set(Nil)))
    }

    "filter with field in empty set" in {
      testTypedLogicalPlanCompile("select * from zips where state in ()",
        lpf.constant(Data.Set(Nil)))
    }

    "compile between" in {
      testLogicalPlanCompile(
        "select * from foo where bar between 1 and 10",
        lpf.let('__tmp0, read("foo"),
          Squash[FLP](
            Filter[FLP](
              lpf.free('__tmp0),
              Between[FLP](
                ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
                lpf.constant(Data.Int(1)),
                lpf.constant(Data.Int(10)))))))
    }

    "compile not between" in {
      testLogicalPlanCompile(
        "select * from foo where bar not between 1 and 10",
        lpf.let('__tmp0, read("foo"),
          Squash[FLP](
            Filter[FLP](
              lpf.free('__tmp0),
              Not[FLP](
                Between[FLP](
                  ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
                  lpf.constant(Data.Int(1)),
                  lpf.constant(Data.Int(10))))))))
    }

    "compile like" in {
      testLogicalPlanCompile(
        "select bar from foo where bar like \"a%\"",
        lpf.let('__tmp0, read("foo"),
          Squash(
            makeObj(
              "bar" ->
                ObjectProject[FLP](
                  Filter[FLP](
                    lpf.free('__tmp0),
                    Search[FLP](
                      ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
                      lpf.constant(Data.Str("^a.*$")),
                      lpf.constant(Data.Bool(false)))),
                  lpf.constant(Data.Str("bar")))))))
    }

    "compile like with escape char" in {
      testLogicalPlanCompile(
        "select bar from foo where bar like \"a=%\" escape \"=\"",
        lpf.let('__tmp0, read("foo"),
          Squash(
            makeObj(
              "bar" ->
                ObjectProject[FLP](
                  Filter[FLP](
                    lpf.free('__tmp0),
                    Search[FLP](
                      ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
                      lpf.constant(Data.Str("^a%$")),
                      lpf.constant(Data.Bool(false)))),
                  lpf.constant(Data.Str("bar")))))))
    }

    "compile not like" in {
      testLogicalPlanCompile(
        "select bar from foo where bar not like \"a%\"",
        lpf.let('__tmp0, read("foo"),
          Squash(makeObj("bar" -> ObjectProject[FLP](Filter[FLP](
            lpf.free('__tmp0),
            Not[FLP](
              Search[FLP](
                ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
                lpf.constant(Data.Str("^a.*$")),
                lpf.constant(Data.Bool(false))))),
            lpf.constant(Data.Str("bar")))))))
    }

    "compile ~" in {
      testLogicalPlanCompile(
        "select bar from foo where bar ~ \"a.$\"",
        lpf.let('__tmp0, read("foo"),
          Squash(
            makeObj(
              "bar" ->
                ObjectProject[FLP](
                  Filter[FLP](
                    lpf.free('__tmp0),
                    Search[FLP](
                      ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
                      lpf.constant(Data.Str("a.$")),
                      lpf.constant(Data.Bool(false)))),
                  lpf.constant(Data.Str("bar")))))))
    }

    "compile complex expression" in {
      testLogicalPlanCompile(
        "select avgTemp*9/5 + 32 from cities",
        Squash(
          makeObj(
            "0" ->
              Add[FLP](
                Divide[FLP](
                  Multiply[FLP](
                    ObjectProject(read("cities"), lpf.constant(Data.Str("avgTemp"))),
                    lpf.constant(Data.Int(9))),
                  lpf.constant(Data.Int(5))),
                lpf.constant(Data.Int(32))))))
    }

    "compile parenthesized expression" in {
      testLogicalPlanCompile(
        "select (avgTemp + 32)/5 from cities",
        Squash(
          makeObj(
            "0" ->
              Divide[FLP](
                Add[FLP](
                  ObjectProject(read("cities"), lpf.constant(Data.Str("avgTemp"))),
                  lpf.constant(Data.Int(32))),
                lpf.constant(Data.Int(5))))))
    }

    "compile cross select *" in {
      testLogicalPlanCompile(
        "select * from person, car",
        lpf.let('__tmp0,
          InnerJoin(read("person"), read("car"), lpf.constant(Data.Bool(true))),
          Squash[FLP](
            ObjectConcat[FLP](
              ObjectProject(lpf.free('__tmp0), JoinDir.Left.const[Fix]),
              ObjectProject(lpf.free('__tmp0), JoinDir.Right.const[Fix])))))
    }

    "compile two term multiplication from two tables" in {
      testLogicalPlanCompile(
        "select person.age * car.modelYear from person, car",
        lpf.let('__tmp0,
          InnerJoin(read("person"), read("car"), lpf.constant(Data.Bool(true))),
          Squash(
            makeObj(
              "0" ->
                Multiply[FLP](
                  ObjectProject[FLP](
                    ObjectProject(lpf.free('__tmp0), JoinDir.Left.const[Fix]),
                    lpf.constant(Data.Str("age"))),
                  ObjectProject[FLP](
                    ObjectProject(lpf.free('__tmp0), JoinDir.Right.const[Fix]),
                    lpf.constant(Data.Str("modelYear"))))))))
    }

    "compile simple where (with just a constant)" in {
      testLogicalPlanCompile(
        "select name from person where 1",
        Squash(
          makeObj(
            "name" ->
              ObjectProject[FLP](
                Filter(read("person"), lpf.constant(Data.Int(1))),
                lpf.constant(Data.Str("name"))))))
    }

    "compile simple where" in {
      testLogicalPlanCompile(
        "select name from person where age > 18",
        lpf.let('__tmp0, read("person"),
          Squash(
            makeObj(
              "name" ->
                ObjectProject[FLP](
                  Filter[FLP](
                    lpf.free('__tmp0),
                    Gt[FLP](
                      ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("age"))),
                      lpf.constant(Data.Int(18)))),
                  lpf.constant(Data.Str("name")))))))
    }

    "compile simple group by" in {
      testLogicalPlanCompile(
        "select count(*) from person group by name",
        lpf.let('__tmp0, read("person"),
          Squash(
            makeObj(
              "0" ->
                Count[FLP](
                  GroupBy[FLP](
                    lpf.free('__tmp0),
                    MakeArrayN[Fix](ObjectProject(
                      lpf.free('__tmp0),
                      lpf.constant(Data.Str("name"))))))))))
    }

    "compile group by with projected keys" in {
      testLogicalPlanCompile(
        "select lower(name), person.gender, avg(age) from person group by lower(person.name), gender",
        lpf.let('__tmp0, read("person"),
          lpf.let('__tmp1,
            GroupBy[FLP](
              lpf.free('__tmp0),
              MakeArrayN[Fix](
                Lower[FLP](
                  ObjectProject(
                    lpf.free('__tmp0),
                    lpf.constant(Data.Str("name")))),
                ObjectProject(
                  lpf.free('__tmp0),
                  lpf.constant(Data.Str("gender"))))),
            Squash(
              makeObj(
                "0" ->
                  Arbitrary[FLP](
                    Lower[FLP](
                      ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("name"))))),
                "gender" ->
                  Arbitrary[FLP](
                    ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("gender")))),
                "2" ->
                  Avg[FLP](
                    ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("age")))))))))
    }

    "compile group by with perverse aggregated expression" in {
      testLogicalPlanCompile(
        "select count(name) from person group by name",
        lpf.let('__tmp0, read("person"),
          Squash(
            makeObj(
              "0" ->
                Count[FLP](
                  ObjectProject[FLP](
                    GroupBy[FLP](
                      lpf.free('__tmp0),
                      MakeArrayN[Fix](ObjectProject(
                        lpf.free('__tmp0),
                        lpf.constant(Data.Str("name"))))),
                    lpf.constant(Data.Str("name"))))))))
    }

    "compile sum in expression" in {
      testLogicalPlanCompile(
        "select sum(pop) * 100 from zips",
        Squash(
          makeObj(
            "0" ->
              Multiply[FLP](
                Sum[FLP](ObjectProject(read("zips"), lpf.constant(Data.Str("pop")))),
                lpf.constant(Data.Int(100))))))
    }

    val setA =
      lpf.let('__tmp0, read("zips"),
        Squash(makeObj(
          "loc" -> ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("loc"))),
          "pop" -> ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("pop"))))))
    val setB =
      Squash(makeObj(
        "city" -> ObjectProject(read("zips"), lpf.constant(Data.Str("city")))))

    "compile union" in {
      testLogicalPlanCompile(
        "select loc, pop from zips union select city from zips",
          lpf.normalizeLets(lpf.normalizeLets(
            Distinct[FLP](Union[FLP](setA, setB)))))
    }

    "compile union all" in {
      testLogicalPlanCompile(
        "select loc, pop from zips union all select city from zips",
        lpf.normalizeLets(lpf.normalizeLets(
          Union[FLP](setA, setB))))
    }

    "compile intersect" in {
      testLogicalPlanCompile(
        "select loc, pop from zips intersect select city from zips",
        lpf.normalizeLets(lpf.normalizeLets(
          Distinct[FLP](Intersect[FLP](setA, setB)))))
    }

    "compile intersect all" in {
      testLogicalPlanCompile(
        "select loc, pop from zips intersect all select city from zips",
        lpf.normalizeLets(lpf.normalizeLets(
          Intersect[FLP](setA, setB))))
    }

    "compile except" in {
      testLogicalPlanCompile(
        "select loc, pop from zips except select city from zips",
        lpf.normalizeLets(lpf.normalizeLets(
          Except[FLP](setA, setB))))
    }

    "have {*} as alias for {:*}" in {
      testLogicalPlanCompile(
                   "SELECT bar{*} FROM foo",
        compileExp("SELECT bar{:*} FROM foo"))
    }

    "have [*] as alias for [:*]" in {
      testLogicalPlanCompile(
                   "SELECT foo[*] FROM foo",
        compileExp("SELECT foo[:*] FROM foo"))
    }

    "expand top-level map flatten" in {
      testLogicalPlanCompile(
                   "SELECT foo{:*} FROM foo",
        compileExp("SELECT Flatten_Map(foo) AS `0` FROM foo"))
    }

    "expand nested map flatten" in {
      testLogicalPlanCompile(
                   "SELECT foo.bar{:*} FROM foo",
        compileExp("SELECT Flatten_Map(foo.bar) AS `bar` FROM foo"))
    }

    "expand field map flatten" in {
      testLogicalPlanCompile(
                   "SELECT bar{:*} FROM foo",
        compileExp("SELECT Flatten_Map(foo.bar) AS `bar` FROM foo"))
    }

    "expand top-level array flatten" in {
      testLogicalPlanCompile(
                   "SELECT foo[:*] FROM foo",
        compileExp("SELECT Flatten_Array(foo) AS `0` FROM foo"))
    }

    "expand nested array flatten" in {
      testLogicalPlanCompile(
        "SELECT foo.bar[:*] FROM foo",
        compileExp("SELECT Flatten_Array(foo.bar) AS `bar` FROM foo"))
    }

    "expand field array flatten" in {
      testLogicalPlanCompile(
                   "SELECT bar[:*] FROM foo",
        compileExp("SELECT Flatten_Array(foo.bar) AS `bar` FROM foo"))
    }

    "compile top-level map flatten" in {
      testLogicalPlanCompile(
        "select zips{:*} from zips",
        Squash(makeObj("0" -> FlattenMap(read("zips")))))
    }

    "have {_} as alias for {:_}" in {
      testLogicalPlanCompile(
                   "select length(commit.author{_}) from slamengine_commits",
        compileExp("select length(commit.author{:_}) from slamengine_commits"))
    }

    "have [_] as alias for [:_]" in {
      testLogicalPlanCompile(
                   "select loc[_] / 10 from zips",
        compileExp("select loc[:_] / 10 from zips"))
    }

    "compile map shift / unshift" in {
      val inner = ShiftMap[FLP](ObjectProject[FLP](ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("commit"))), lpf.constant(Data.Str("author"))))

      testLogicalPlanCompile(
        "select {commit.author{:_}: length(commit.author{:_}) ...} from slamengine_commits",
        lpf.let('__tmp0, read("slamengine_commits"),
          Squash(makeObj("0" ->
            UnshiftMap[FLP](inner, Length[FLP](inner))))))
    }

    "compile map shift / unshift keys" in {
      val inner = ShiftMapKeys[FLP](ObjectProject[FLP](ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("commit"))), lpf.constant(Data.Str("author"))))

      testLogicalPlanCompile(
        "select {commit.author{_:}: length(commit.author{_:})...} from slamengine_commits",
        lpf.let('__tmp0, read("slamengine_commits"),
          Squash(makeObj("0" ->
            UnshiftMap[FLP](inner, Length[FLP](inner))))))
    }

    "compile array shift / unshift" in {
      testLogicalPlanCompile(
        "select [loc[:_] / 10 ...] from zips",
        Squash(
          makeObj(
            "0" ->
              UnshiftArray[FLP](
                Divide[FLP](
                  ShiftArray[FLP](ObjectProject(read("zips"), lpf.constant(Data.Str("loc")))),
                  lpf.constant(Data.Int(10)))))))
    }

    "compile array shift / unshift indices" in {
      testLogicalPlanCompile(
        "select [loc[_:] * 10 ...] from zips",
        Squash(
          makeObj(
            "0" ->
              UnshiftArray[FLP](
                Multiply[FLP](
                  ShiftArrayIndices[FLP](ObjectProject(read("zips"), lpf.constant(Data.Str("loc")))),
                  lpf.constant(Data.Int(10)))))))
    }

    "compile array flatten" in {
      testLogicalPlanCompile(
        "select loc[:*] from zips",
        Squash(
          makeObj(
            "loc" ->
              FlattenArray[FLP](ObjectProject(read("zips"), lpf.constant(Data.Str("loc")))))))
    }

    "compile simple order by" in {
      testLogicalPlanCompile(
        "select name from person order by height",
        lpf.let('__tmp0, read("person"),
          lpf.let('__tmp1,
            Squash(
              makeObj(
                "name" -> ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("name"))),
                "__sd__0" -> ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("height"))))),
            DeleteField[FLP](
              lpf.sort(
                lpf.free('__tmp1),
                (ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("__sd__0"))).embed, SortDir.asc).wrapNel),
              lpf.constant(Data.Str("__sd__0"))))))
    }

    "compile simple order by with filter" in {
      testLogicalPlanCompile(
        "select name from person where gender = \"male\" order by name, height",
        lpf.let('__tmp0, read("person"),
          lpf.let('__tmp1,
            Filter[FLP](
              lpf.free('__tmp0),
              Eq[FLP](
                ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("gender"))),
                lpf.constant(Data.Str("male")))),
            lpf.let('__tmp2,
              Squash(
                makeObj(
                  "name"    -> ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("name"))),
                  "__sd__0" -> ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("height"))))),
              DeleteField[FLP](
                lpf.sort(
                  lpf.free('__tmp2),
                  NonEmptyList(
                    (ObjectProject(lpf.free('__tmp2), lpf.constant(Data.Str("name"))).embed, SortDir.asc),
                    (ObjectProject(lpf.free('__tmp2), lpf.constant(Data.Str("__sd__0"))).embed, SortDir.asc))),
                lpf.constant(Data.Str("__sd__0")))))))
    }

    "compile simple order by with wildcard" in {
      testLogicalPlanCompile(
        "select * from person order by height",
        lpf.let('__tmp0, Squash(read("person")),
          lpf.sort(
            lpf.free('__tmp0),
            (ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("height"))).embed, SortDir.asc).wrapNel)))
    }

    "compile simple order by with ascending and descending" in {
      testLogicalPlanCompile(
        "select * from person order by height desc, name",
        lpf.let('__tmp0, Squash(read("person")),
          lpf.sort(
            lpf.free('__tmp0),
            NonEmptyList(
              (ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("height"))).embed, SortDir.desc),
              (ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("name"))).embed, SortDir.asc)))))
    }

    "compile simple order by with expression" in {
      testLogicalPlanCompile(
        "select * from person order by height*2.54",
        lpf.let('__tmp0, read("person"),
          lpf.let('__tmp1,
            Squash[FLP](
              ObjectConcat(
                lpf.free('__tmp0),
                makeObj(
                  "__sd__0" -> Multiply[FLP](
                    ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("height"))),
                    lpf.constant(Data.Dec(2.54)))))),
            DeleteField[FLP](
              lpf.sort(
                lpf.free('__tmp1),
                (ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("__sd__0"))).embed, SortDir.asc).wrapNel),
              lpf.constant(Data.Str("__sd__0"))))))
    }

    "compile order by with alias" in {
      testLogicalPlanCompile(
        "select firstName as name from person order by name",
        lpf.let('__tmp0,
          Squash(
            makeObj(
              "name" -> ObjectProject(read("person"), lpf.constant(Data.Str("firstName"))))),
          lpf.sort(
            lpf.free('__tmp0),
            (ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("name"))).embed, SortDir.asc).wrapNel)))
    }

    "compile simple order by with expression in synthetic field" in {
      testLogicalPlanCompile(
        "select name from person order by height*2.54",
        lpf.let('__tmp0, read("person"),
          lpf.let('__tmp1,
            Squash(
              makeObj(
                "name" -> ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("name"))),
                "__sd__0" ->
                  Multiply[FLP](
                    ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("height"))),
                    lpf.constant(Data.Dec(2.54))))),
            DeleteField[FLP](
              lpf.sort(
                lpf.free('__tmp1),
                (ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("__sd__0"))).embed, SortDir.asc).wrapNel),
              lpf.constant(Data.Str("__sd__0"))))))
    }

    "compile order by with nested projection" in {
      testLogicalPlanCompile(
        "select bar from foo order by foo.bar.baz.quux/3",
        lpf.let('__tmp0, read("foo"),
          lpf.let('__tmp1,
            Squash(
              makeObj(
                "bar" -> ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("bar"))),
                "__sd__0" -> Divide[FLP](
                  ObjectProject[FLP](
                    ObjectProject[FLP](
                      ObjectProject(lpf.free('__tmp0),
                        lpf.constant(Data.Str("bar"))),
                      lpf.constant(Data.Str("baz"))),
                    lpf.constant(Data.Str("quux"))),
                  lpf.constant(Data.Int(3))))),
            DeleteField[FLP](
              lpf.sort(
                lpf.free('__tmp1),
                (ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("__sd__0"))).embed, SortDir.asc).wrapNel),
              lpf.constant(Data.Str("__sd__0"))))))
    }

    "compile order by with root projection a table ref" in {
      // Note: not using wildcard here because the simple case is optimized
      //       differently
      testLogicalPlanCompile(
                   "select foo from bar order by bar.baz",
        compileExp("select foo from bar order by baz"))
    }

    "compile order by with root projection a table ref with alias" in {
      // Note: not using wildcard here because the simple case is optimized
      //       differently
      testLogicalPlanCompile(
                   "select foo from bar as b order by b.baz",
        compileExp("select foo from bar as b order by baz"))
    }

    "compile order by with root projection a table ref with alias, mismatched" in {
      testLogicalPlanCompile(
                   "select * from bar as b order by bar.baz",
        compileExp("select * from bar as b order by b.bar.baz"))
    }

    "compile order by with root projection a table ref, embedded in expr" in {
      testLogicalPlanCompile(
                   "select * from bar order by bar.baz/10",
        compileExp("select * from bar order by baz/10"))
    }

    "compile order by with root projection a table ref, embedded in complex expr" in {
      testLogicalPlanCompile(
                   "select * from bar order by bar.baz/10 - 3*bar.quux",
        compileExp("select * from bar order by baz/10 - 3*quux"))
    }

    "compile multiple stages" in {
      testLogicalPlanCompile(
        "select height*2.54 as cm" +
          " from person" +
          " where height > 60" +
          " group by gender, height" +
          " having count(*) > 10" +
          " order by cm" +
          " offset 10" +
          " limit 5",
        lpf.let('__tmp0, read("person"), // from person
          lpf.let('__tmp1,    // where height > 60
            Filter[FLP](
              lpf.free('__tmp0),
              Gt[FLP](
                ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("height"))),
                lpf.constant(Data.Int(60)))),
            lpf.let('__tmp2,    // group by gender, height
              GroupBy[FLP](
                lpf.free('__tmp1),
                MakeArrayN[Fix](
                  ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("gender"))),
                  ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("height"))))),
              lpf.let('__tmp3,
                Squash(    // select height*2.54 as cm
                  makeObj(
                    "cm" ->
                      Multiply[FLP](
                        Arbitrary[FLP](
                          ObjectProject[FLP](
                            Filter[FLP](  // having count(*) > 10
                              lpf.free('__tmp2),
                              Gt[FLP](Count(lpf.free('__tmp2)), lpf.constant(Data.Int(10)))),
                            lpf.constant(Data.Str("height")))),
                        lpf.constant(Data.Dec(2.54))))),
                Take[FLP](
                  Drop[FLP](
                    lpf.sort(  // order by cm
                      lpf.free('__tmp3),
                      (ObjectProject(lpf.free('__tmp3), lpf.constant(Data.Str("cm"))).embed, SortDir.asc).wrapNel),
                  lpf.constant(Data.Int(10))), // offset 10
                lpf.constant(Data.Int(5))))))))    // limit 5
    }

    "compile simple sum" in {
      testLogicalPlanCompile(
        "select sum(height) from person",
        Squash(
          makeObj(
            "0" ->
              Sum[FLP](ObjectProject(read("person"), lpf.constant(Data.Str("height")))))))
    }

    "compile simple inner equi-join" in {
      testLogicalPlanCompile(
        "select foo.name, bar.address from foo join bar on foo.id = bar.foo_id",
        lpf.let('__tmp0, read("foo"),
          lpf.let('__tmp1, read("bar"),
            lpf.let('__tmp2,
              InnerJoin[FLP](lpf.free('__tmp0), lpf.free('__tmp1),
                relations.Eq[FLP](
                  ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("id"))),
                  ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("foo_id"))))),
              Squash(
                makeObj(
                  "name" ->
                    ObjectProject[FLP](
                      ObjectProject(lpf.free('__tmp2), JoinDir.Left.const[Fix]),
                      lpf.constant(Data.Str("name"))),
                  "address" ->
                    ObjectProject[FLP](
                      ObjectProject(lpf.free('__tmp2), JoinDir.Right.const[Fix]),
                      lpf.constant(Data.Str("address")))))))))
    }

    "compile cross join to the equivalent inner equi-join" in {
      val query = "select foo.name, bar.address from foo, bar where foo.id = bar.foo_id"
      val equiv = "select foo.name, bar.address from foo join bar on foo.id = bar.foo_id"

      testLogicalPlanCompile(query, compileExp(equiv))
    }

    "compile inner join with additional equi-condition to the equivalent inner equi-join" in {
      val query = "select foo.name, bar.address from foo join bar on foo.id = bar.foo_id where foo.x = bar.y"
      val equiv = "select foo.name, bar.address from foo join bar on foo.id = bar.foo_id and foo.x = bar.y"

      testLogicalPlanCompile(query, compileExp(equiv))
    }

    "compile inner non-equi join to the equivalent cross join" in {
      val query = "select foo.name, bar.address from foo join bar on foo.x < bar.y"
      val equiv = "select foo.name, bar.address from foo, bar where foo.x < bar.y"

      testLogicalPlanCompile(query, compileExp(equiv))
    }

    "compile nested cross join to the equivalent inner equi-join" in {
      val query = "select a.x, b.y, c.z from a, b, c where a.id = b.a_id and b._id = c.b_id"
      val equiv = "select a.x, b.y, c.z from (a join b on a.id = b.a_id) join c on b._id = c.b_id"

      testLogicalPlanCompile(query, compileExp(equiv))
    }.pendingUntilFixed("SD-1190")

    "compile filtered cross join with one-sided conditions" in {
      val query = "select foo.name, bar.address from foo, bar where foo.id = bar.foo_id and foo.x < 10 and bar.y = 20"

      // NB: this query produces what we want but currently doesn't match due to spurious SQUASHes
      // val equiv = "select foo.name, bar.address from (select * from foo where x < 10) foo join (select * from bar where y = 20) bar on foo.id = bar.foo_id"

      testLogicalPlanCompile(query,
        lpf.let('__tmp0, read("foo"),
          lpf.let('__tmp1,
             Fix(Filter(
               lpf.free('__tmp0),
               Fix(Lt(
                 ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("x"))),
                 lpf.constant(Data.Int(10)))))),
             lpf.let('__tmp2, read("bar"),
               lpf.let('__tmp3,
                 Fix(Filter(
                   lpf.free('__tmp2),
                   Fix(Eq(
                     ObjectProject(lpf.free('__tmp2), lpf.constant(Data.Str("y"))),
                     lpf.constant(Data.Int(20)))))),
                  lpf.let('__tmp4,
                    Fix(InnerJoin(lpf.free('__tmp1), lpf.free('__tmp3),
                      Fix(Eq(
                        ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("id"))),
                        ObjectProject(lpf.free('__tmp3), lpf.constant(Data.Str("foo_id"))))))),
                    Fix(Squash(
                       makeObj(
                         "name" -> ObjectProject[FLP](
                           ObjectProject(lpf.free('__tmp4), JoinDir.Left.const[Fix]),
                           lpf.constant(Data.Str("name"))),
                         "address" -> ObjectProject[FLP](
                           ObjectProject(lpf.free('__tmp4), JoinDir.Right.const[Fix]),
                           lpf.constant(Data.Str("address"))))))))))))
    }

    "compile filtered join with one-sided conditions" in {
      val query = "select foo.name, bar.address from foo join bar on foo.id = bar.foo_id where foo.x < 10 and bar.y = 20"

      // NB: this should be identical to the same query written as a cross join
      // (but cannot be written as in "must compile to the equivalent ..." style
      // because both require optimization)

      testLogicalPlanCompile(query,
        lpf.let('__tmp0, read("foo"),
          lpf.let('__tmp1, read("bar"),
            lpf.let('__tmp2,
              Fix(Filter(
                lpf.free('__tmp0),
                Fix(Lt(
                  ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("x"))),
                  lpf.constant(Data.Int(10)))))),
              lpf.let('__tmp3,
                Fix(Filter(
                  lpf.free('__tmp1),
                  Fix(Eq(
                    ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("y"))),
                    lpf.constant(Data.Int(20)))))),
                lpf.let('__tmp4,
                  Fix(InnerJoin(lpf.free('__tmp2), lpf.free('__tmp3),
                    Fix(Eq(
                      ObjectProject(lpf.free('__tmp2), lpf.constant(Data.Str("id"))),
                      ObjectProject(lpf.free('__tmp3), lpf.constant(Data.Str("foo_id"))))))),
                  Fix(Squash(
                    makeObj(
                      "name" -> ObjectProject[FLP](
                        ObjectProject(lpf.free('__tmp4), JoinDir.Left.const[Fix]),
                        lpf.constant(Data.Str("name"))),
                      "address" -> ObjectProject[FLP](
                        ObjectProject(lpf.free('__tmp4), JoinDir.Right.const[Fix]),
                        lpf.constant(Data.Str("address"))))))))))))
    }

    "compile simple left ineq-join" in {
      testLogicalPlanCompile(
        "select foo.name, bar.address " +
          "from foo left join bar on foo.id < bar.foo_id",
        lpf.let('__tmp0, read("foo"),
          lpf.let('__tmp1, read("bar"),
            lpf.let('__tmp2,
              LeftOuterJoin[FLP](lpf.free('__tmp0), lpf.free('__tmp1),
                relations.Lt[FLP](
                  ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("id"))),
                  ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("foo_id"))))),
              Squash(
                makeObj(
                  "name" ->
                    ObjectProject[FLP](
                      ObjectProject(lpf.free('__tmp2), JoinDir.Left.const[Fix]),
                      lpf.constant(Data.Str("name"))),
                  "address" ->
                    ObjectProject[FLP](
                      ObjectProject(lpf.free('__tmp2), JoinDir.Right.const[Fix]),
                      lpf.constant(Data.Str("address")))))))))
    }

    "compile complex equi-join" in {
      testLogicalPlanCompile(
        "select foo.name, bar.address " +
          "from foo join bar on foo.id = bar.foo_id " +
          "join baz on baz.bar_id = bar.id",
        lpf.let('__tmp0, read("foo"),
          lpf.let('__tmp1, read("bar"),
            lpf.let('__tmp2,
              InnerJoin[FLP](lpf.free('__tmp0), lpf.free('__tmp1),
                relations.Eq[FLP](
                  ObjectProject(
                    lpf.free('__tmp0),
                    lpf.constant(Data.Str("id"))),
                  ObjectProject(
                    lpf.free('__tmp1),
                    lpf.constant(Data.Str("foo_id"))))),
              lpf.let('__tmp3, read("baz"),
                lpf.let('__tmp4,
                  InnerJoin[FLP](lpf.free('__tmp2), lpf.free('__tmp3),
                    relations.Eq[FLP](
                      ObjectProject(lpf.free('__tmp3),
                        lpf.constant(Data.Str("bar_id"))),
                      ObjectProject[FLP](
                        ObjectProject(lpf.free('__tmp2),
                          JoinDir.Right.const[Fix]),
                        lpf.constant(Data.Str("id"))))),
                  Squash(
                    makeObj(
                      "name" ->
                        ObjectProject[FLP](
                          ObjectProject[FLP](
                            ObjectProject(lpf.free('__tmp4), JoinDir.Left.const[Fix]),
                            JoinDir.Left.const[Fix]),
                          lpf.constant(Data.Str("name"))),
                      "address" ->
                        ObjectProject[FLP](
                          ObjectProject[FLP](
                            ObjectProject(lpf.free('__tmp4), JoinDir.Left.const[Fix]),
                            JoinDir.Right.const[Fix]),
                          lpf.constant(Data.Str("address")))))))))))
    }

    "compile sub-select in filter" in {
      testLogicalPlanCompile(
        "select city, pop from zips where pop > (select avg(pop) from zips)",
        read("zips"))
    }.pendingUntilFixed

    "compile simple sub-select" in {
      testLogicalPlanCompile(
        "select temp.name, temp.size from (select zips.city as name, zips.pop as size from zips) as temp",
        lpf.let('__tmp0, read("zips"),
          lpf.let('__tmp1,
            Squash(
              makeObj(
                "name" -> ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("city"))),
                "size" -> ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("pop"))))),
            Squash(
              makeObj(
                "name" -> ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("name"))),
                "size" -> ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("size"))))))))
    }

    "compile sub-select with same un-qualified names" in {
      testLogicalPlanCompile(
        "select city, pop from (select city, pop from zips) as temp",
        lpf.let('__tmp0, read("zips"),
          lpf.let('__tmp1,
            Squash(
              makeObj(
                "city" -> ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("city"))),
                "pop" -> ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("pop"))))),
            Squash(
              makeObj(
                "city" -> ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("city"))),
                "pop" -> ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("pop"))))))))
    }

    "compile simple distinct" in {
      testLogicalPlanCompile(
        "select distinct city from zips",
        Distinct[FLP](
          Squash(
            makeObj(
              "city" -> ObjectProject(read("zips"), lpf.constant(Data.Str("city")))))))
    }

    "compile simple distinct ordered" in {
      testLogicalPlanCompile(
        "select distinct city from zips order by city",
        lpf.let('__tmp0,
          Squash(
            makeObj(
              "city" ->
                ObjectProject(read("zips"), lpf.constant(Data.Str("city"))))),
          Distinct[FLP](
            lpf.sort(
              lpf.free('__tmp0),
              (ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("city"))).embed, SortDir.asc).wrapNel))))
    }

    "compile distinct with unrelated order by" in {
      testLogicalPlanCompile(
        "select distinct city from zips order by pop desc",
        lpf.let('__tmp0,
          read("zips"),
          lpf.let('__tmp1,
            Squash(
              makeObj(
                "city" -> ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("city"))),
                "__sd__0" -> ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("pop"))))),
            lpf.let('__tmp2,
              lpf.sort(
                lpf.free('__tmp1),
                (ObjectProject(lpf.free('__tmp1), lpf.constant(Data.Str("__sd__0"))).embed, SortDir.desc).wrapNel),
              DeleteField[FLP](
                DistinctBy[FLP](lpf.free('__tmp2),
                  DeleteField(lpf.free('__tmp2), lpf.constant(Data.Str("__sd__0")))),
                lpf.constant(Data.Str("__sd__0")))))))
    }

    "compile count(distinct(...))" in {
      testLogicalPlanCompile(
        "select count(distinct(lower(city))) from zips",
        Squash(
          makeObj(
            "0" -> Count[FLP](Distinct[FLP](Lower[FLP](ObjectProject(read("zips"), lpf.constant(Data.Str("city")))))))))
    }

    "compile simple distinct with two named projections" in {
      testLogicalPlanCompile(
        "select distinct city as CTY, state as ST from zips",
        lpf.let('__tmp0, read("zips"),
          Distinct[FLP](
            Squash(
              makeObj(
                "CTY" -> ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("city"))),
                "ST" -> ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("state"))))))))
    }

    "compile count distinct with two exprs" in {
      testLogicalPlanCompile(
        "select count(distinct city, state) from zips",
        read("zips"))
    }.pendingUntilFixed

    "compile distinct as function" in {
      testLogicalPlanCompile(
        "select distinct(city, state) from zips",
        read("zips"))
    }.pendingUntilFixed

    "fail with ambiguous reference" in {
      compile("select foo from bar, baz") must beLeftDisjunction
    }

    "fail with ambiguous reference in cond" in {
      compile("select (case when a = 1 then 'ok' else 'reject' end) from bar, baz") must beLeftDisjunction
    }

    "fail with ambiguous reference in else" in {
      compile("select (case when bar.a = 1 then 'ok' else foo end) from bar, baz") must beLeftDisjunction
    }

    "fail with duplicate alias" in {
      compile("select car.name as name, owner.name as name from owners as owner join cars as car on car._id = owner.carId") must
        beLeftDisjunction("DuplicateAlias(name)")
    }

    "translate free variable" in {
      testLogicalPlanCompile("select name from zips where age < :age",
        lpf.let('__tmp0, read("zips"),
          Squash(
            makeObj(
              "name" ->
                ObjectProject[FLP](
                  Filter[FLP](
                    lpf.free('__tmp0),
                    Lt[FLP](
                      ObjectProject(lpf.free('__tmp0), lpf.constant(Data.Str("age"))),
                      lpf.free('age))),
                  lpf.constant(Data.Str("name")))))))
    }
  }

  "error when too few arguments passed to a function" in {
    fullCompile("""select substring("foo") from zips""")
      .toEither must beLeft(contain("3,1"))
  }

  "error when too many arguments passed to a function" in {
    fullCompile("select count(*, 1, 2, 4) from zips")
      .toEither must beLeft(contain("1,4"))
  }

  "reduceGroupKeys" should {
    import Compiler.reduceGroupKeys

    "insert ARBITRARY" in {
      val lp =
        lpf.let('tmp0, read("zips"),
          lpf.let('tmp1,
            GroupBy[FLP](
              lpf.free('tmp0),
              MakeArrayN[Fix](ObjectProject(lpf.free('tmp0), lpf.constant(Data.Str("city"))))),
            ObjectProject(lpf.free('tmp1), lpf.constant(Data.Str("city")))))
      val exp =
        lpf.let('tmp0, read("zips"),
          Arbitrary[FLP](
            ObjectProject[FLP](
              GroupBy[FLP](
                lpf.free('tmp0),
                MakeArrayN[Fix](ObjectProject(lpf.free('tmp0), lpf.constant(Data.Str("city"))))), lpf.constant(Data.Str("city")))))

      reduceGroupKeys(lp) must equalToPlan(exp)
    }

    "insert ARBITRARY with intervening filter" in {
      val lp =
        lpf.let('tmp0, read("zips"),
          lpf.let('tmp1,
            GroupBy[FLP](
              lpf.free('tmp0),
              MakeArrayN[Fix](ObjectProject(lpf.free('tmp0), lpf.constant(Data.Str("city"))))),
            lpf.let('tmp2,
              Filter[FLP](lpf.free('tmp1), Gt[FLP](Count(lpf.free('tmp1)), lpf.constant(Data.Int(10)))),
              ObjectProject(lpf.free('tmp2), lpf.constant(Data.Str("city"))))))
      val exp =
        lpf.let('tmp0, read("zips"),
          lpf.let('tmp1,
            GroupBy[FLP](
              lpf.free('tmp0),
              MakeArrayN[Fix](ObjectProject(lpf.free('tmp0), lpf.constant(Data.Str("city"))))),
            Arbitrary[FLP](
              ObjectProject[FLP](
                Filter[FLP](
                  lpf.free('tmp1),
                  Gt[FLP](Count(lpf.free('tmp1)), lpf.constant(Data.Int(10)))),
                lpf.constant(Data.Str("city"))))))

      reduceGroupKeys(lp) must equalToPlan(exp)
    }

    "not insert redundant Reduction" in {
      val lp =
        lpf.let('tmp0, read("zips"),
          Count[FLP](
            ObjectProject[FLP](
              GroupBy[FLP](
                lpf.free('tmp0),
                MakeArrayN[Fix](ObjectProject(lpf.free('tmp0),
                  lpf.constant(Data.Str("city"))))), lpf.constant(Data.Str("city")))))

      reduceGroupKeys(lp) must equalToPlan(lp)
    }

    "insert ARBITRARY with multiple keys and mixed projections" in {
      val lp =
        lpf.let('tmp0,
          read("zips"),
          lpf.let('tmp1,
            GroupBy[FLP](
              lpf.free('tmp0),
              MakeArrayN[Fix](
                ObjectProject(lpf.free('tmp0), lpf.constant(Data.Str("city"))),
                ObjectProject(lpf.free('tmp0), lpf.constant(Data.Str("state"))))),
            makeObj(
              "city" -> ObjectProject(lpf.free('tmp1), lpf.constant(Data.Str("city"))),
              "1"    -> Count[FLP](ObjectProject(lpf.free('tmp1), lpf.constant(Data.Str("state")))),
              "loc"  -> ObjectProject(lpf.free('tmp1), lpf.constant(Data.Str("loc"))),
              "2"    -> Sum[FLP](ObjectProject(lpf.free('tmp1), lpf.constant(Data.Str("pop")))))))
      val exp =
        lpf.let('tmp0,
          read("zips"),
          lpf.let('tmp1,
            GroupBy[FLP](
              lpf.free('tmp0),
              MakeArrayN[Fix](
                ObjectProject(lpf.free('tmp0), lpf.constant(Data.Str("city"))),
                ObjectProject(lpf.free('tmp0), lpf.constant(Data.Str("state"))))),
            makeObj(
              "city" -> Arbitrary[FLP](ObjectProject(lpf.free('tmp1), lpf.constant(Data.Str("city")))),
              "1"    -> Count[FLP](ObjectProject(lpf.free('tmp1), lpf.constant(Data.Str("state")))),
              "loc"  -> ObjectProject(lpf.free('tmp1), lpf.constant(Data.Str("loc"))),
              "2"    -> Sum[FLP](ObjectProject(lpf.free('tmp1), lpf.constant(Data.Str("pop")))))))

      reduceGroupKeys(lp) must equalToPlan(exp)
    }
  }

  "constant folding" >> {
    def testFolding(name: String, query: String, expected: String) = {
      s"${name}" >> {
        testTypedLogicalPlanCompile(query, fullCompileExp(expected))
      }

      s"${name} with collection" >> {
        testTypedLogicalPlanCompile(s"$query from zips", fullCompileExp(expected))
      }
    }

    testFolding("ARBITRARY",
      "select arbitrary((3, 4, 5))",
      "select 3")

    testFolding("AVG",
      "select avg((0.5, 1.0, 4.5))",
      "select 2.0")

    testFolding("COUNT",
      """select count(("foo", "quux", "baz"))""",
      "select 3")

    testFolding("MAX",
      "select max((4, 2, 1001, 17))",
      "select 1001")

    testFolding("MIN",
      "select min((4, 2, 1001, 17))",
      "select 2")

    testFolding("SUM",
      "select sum((1, 1, 1, 1, 1, 3, 4))",
      "select 12")
  }

  List("avg", "sum") foreach { fn =>
    s"passing a literal set of the wrong type to '${fn.toUpperCase}' fails" >> {
      fullCompile(s"""select $fn(("one", "two", "three"))""") must beLeftDisjunction
    }
  }
}
