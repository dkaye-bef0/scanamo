package com.gu.scanamo

import cats.data.Xor
import com.gu.scanamo.error.DynamoReadError
import com.gu.scanamo.ops.ScanamoOps
import com.gu.scanamo.query._
import com.gu.scanamo.update.UpdateExpression

/**
  * Represents a DynamoDB table that operations can be performed against
  *
  * {{{
  * >>> case class Transport(mode: String, line: String)
  * >>> val transport = Table[Transport]("transport")
  *
  * >>> val client = LocalDynamoDB.client()
  * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
  *
  * >>> LocalDynamoDB.withTable(client)("transport")('mode -> S, 'line -> S) {
  * ...   import com.gu.scanamo.syntax._
  * ...   val operations = for {
  * ...     _ <- transport.putAll(List(
  * ...       Transport("Underground", "Circle"),
  * ...       Transport("Underground", "Metropolitan"),
  * ...       Transport("Underground", "Central")))
  * ...     results <- transport.query('mode -> "Underground" and ('line beginsWith "C"))
  * ...   } yield results.toList
  * ...   Scanamo.exec(client)(operations)
  * ... }
  * List(Right(Transport(Underground,Central)), Right(Transport(Underground,Circle)))
  * }}}
  */
case class Table[V: DynamoFormat](name: String) {
  /**
    * A secondary index on the table which can be scanned, or queried against
    *
    * {{{
    * >>> case class Transport(mode: String, line: String, colour: String)
    * >>> val transport = Table[Transport]("transport")
    *
    * >>> val client = LocalDynamoDB.client()
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    * >>> import com.gu.scanamo.syntax._
    *
    * >>> LocalDynamoDB.withTableWithSecondaryIndex(client)("transport", "colour-index")('mode -> S, 'line -> S)('colour -> S) {
    * ...   val operations = for {
    * ...     _ <- transport.putAll(List(
    * ...       Transport("Underground", "Circle", "Yellow"),
    * ...       Transport("Underground", "Metropolitan", "Magenta"),
    * ...       Transport("Underground", "Central", "Red")))
    * ...     MagentaLine <- transport.index("colour-index").query('colour -> "Magenta")
    * ...   } yield MagentaLine.toList
    * ...   Scanamo.exec(client)(operations)
    * ... }
    * List(Right(Transport(Underground,Metropolitan,Magenta)))
    * }}}
    */
  def index(indexName: String) = Index[V](name, indexName)

  def put(v: V) = ScanamoFree.put(name)(v)
  def putAll(vs: List[V]) = ScanamoFree.putAll(name)(vs)
  def get(key: UniqueKey[_]) = ScanamoFree.get[V](name)(key)
  def getAll(keys: UniqueKeys[_]) = ScanamoFree.getAll[V](name)(keys)
  def delete(key: UniqueKey[_]) = ScanamoFree.delete(name)(key)

  /**
    * {{{
    * >>> case class Forecast(location: String, weather: String)
    * >>> val forecast = Table[Forecast]("forecast")
    *
    * >>> val client = LocalDynamoDB.client()
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    *
    * >>> LocalDynamoDB.withTable(client)("forecast")('location -> S) {
    * ...   import com.gu.scanamo.syntax._
    * ...   val operations = for {
    * ...     _ <- forecast.put(Forecast("London", "Rain"))
    * ...     _ <- forecast.update('location -> "London", set('weather -> "Sun"))
    * ...     results <- forecast.scan()
    * ...   } yield results.toList
    * ...   Scanamo.exec(client)(operations)
    * ... }
    * List(Right(Forecast(London,Sun)))
    * }}}
    */
  def update[T: UpdateExpression](key: UniqueKey[_], expression: T) =
    ScanamoFree.update(name)(key)(expression)

  /**
    * Query or scan a table, limiting the number of items evaluated by Dynamo
    * {{{
    * >>> case class Transport(mode: String, line: String)
    * >>> val transport = Table[Transport]("transport")
    *
    * >>> val client = LocalDynamoDB.client()
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    *
    * >>> LocalDynamoDB.withTable(client)("transport")('mode -> S, 'line -> S) {
    * ...   import com.gu.scanamo.syntax._
    * ...   val operations = for {
    * ...     _ <- transport.putAll(List(
    * ...       Transport("Underground", "Circle"),
    * ...       Transport("Underground", "Metropolitan"),
    * ...       Transport("Underground", "Central")))
    * ...     results <- transport.limit(1).query('mode -> "Underground" and ('line beginsWith "C"))
    * ...   } yield results.toList
    * ...   Scanamo.exec(client)(operations)
    * ... }
    * List(Right(Transport(Underground,Central)))
    * }}}
    */
  def limit(n: Int) = TableLimit(this, n)

  /**
    * Performs the chained operation, `put` if the condition is met
    *
    * {{{
    * >>> case class Farm(animals: List[String])
    * >>> case class Farmer(name: String, age: Long, farm: Farm)
    *
    * >>> import com.gu.scanamo.syntax._
    * >>> import com.gu.scanamo.query._
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    * >>> val client = LocalDynamoDB.client()
    *
    * >>> val farmersTable = Table[Farmer]("nursery-farmers")
    * >>> LocalDynamoDB.withTable(client)("nursery-farmers")('name -> S) {
    * ...   val farmerOps = for {
    * ...     _ <- farmersTable.put(Farmer("McDonald", 156L, Farm(List("sheep", "cow"))))
    * ...     _ <- farmersTable.given('age -> 156L).put(Farmer("McDonald", 156L, Farm(List("sheep", "chicken"))))
    * ...     _ <- farmersTable.given('age -> 15L).put(Farmer("McDonald", 156L, Farm(List("gnu", "chicken"))))
    * ...     farmerWithNewStock <- farmersTable.get('name -> "McDonald")
    * ...   } yield farmerWithNewStock
    * ...   Scanamo.exec(client)(farmerOps)
    * ... }
    * Some(Right(Farmer(McDonald,156,Farm(List(sheep, chicken)))))
    *
    * >>> case class Letter(roman: String, greek: String)
    * >>> val lettersTable = Table[Letter]("letters")
    * >>> LocalDynamoDB.withTable(client)("letters")('roman -> S) {
    * ...   val ops = for {
    * ...     _ <- lettersTable.putAll(List(Letter("a", "alpha"), Letter("b", "beta"), Letter("c", "gammon")))
    * ...     _ <- lettersTable.given('greek beginsWith "ale").put(Letter("a", "aleph"))
    * ...     _ <- lettersTable.given('greek beginsWith "gam").put(Letter("c", "gamma"))
    * ...     letters <- lettersTable.scan()
    * ...   } yield letters
    * ...   Scanamo.exec(client)(ops).toList
    * ... }
    * List(Right(Letter(b,beta)), Right(Letter(c,gamma)), Right(Letter(a,alpha)))
    *
    * >>> import cats.implicits._
    * >>> case class Turnip(size: Int, description: Option[String])
    * >>> val turnipsTable = Table[Turnip]("turnips")
    * >>> LocalDynamoDB.withTable(client)("turnips")('size -> N) {
    * ...   val ops = for {
    * ...     _ <- turnipsTable.putAll(List(Turnip(1, None), Turnip(1000, None)))
    * ...     initialTurnips <- turnipsTable.scan()
    * ...     _ <- initialTurnips.flatMap(_.toOption).traverse(t =>
    * ...       turnipsTable.given('size > 500).put(t.copy(description = Some("Big turnip in the country."))))
    * ...     turnips <- turnipsTable.scan()
    * ...   } yield turnips
    * ...   Scanamo.exec(client)(ops).toList
    * ... }
    * List(Right(Turnip(1,None)), Right(Turnip(1000,Some(Big turnip in the country.))))
    * }}}
    *
    * Conditions can also make use of negation via `not`:
    *
    * {{{
    * >>> case class Thing(a: String, maybe: Option[Int])
    * >>> val thingTable = Table[Thing]("things")
    * >>> LocalDynamoDB.withTable(client)("things")('a -> S) {
    * ...   val ops = for {
    * ...     _ <- thingTable.putAll(List(Thing("a", None), Thing("b", Some(1)), Thing("c", None)))
    * ...     _ <- thingTable.given(attributeExists('maybe)).put(Thing("a", Some(2)))
    * ...     _ <- thingTable.given(attributeExists('maybe)).put(Thing("b", Some(3)))
    * ...     _ <- thingTable.given(Not(attributeExists('maybe))).put(Thing("c", Some(42)))
    * ...     _ <- thingTable.given(Not(attributeExists('maybe))).put(Thing("b", Some(42)))
    * ...     things <- thingTable.scan()
    * ...   } yield things
    * ...   Scanamo.exec(client)(ops).toList
    * ... }
    * List(Right(Thing(b,Some(3))), Right(Thing(c,Some(42))), Right(Thing(a,None)))
    * }}}
    *
    * be combined with `and`
    *
    * {{{
    * >>> case class Compound(a: String, maybe: Option[Int])
    * >>> val compoundTable = Table[Compound]("compounds")
    * >>> LocalDynamoDB.withTable(client)("compounds")('a -> S) {
    * ...   val ops = for {
    * ...     _ <- compoundTable.putAll(List(Compound("alpha", None), Compound("beta", Some(1)), Compound("gamma", None)))
    * ...     _ <- compoundTable.given(attributeExists('maybe) and 'a -> "alpha").put(Compound("alpha", Some(2)))
    * ...     _ <- compoundTable.given(attributeExists('maybe) and 'a -> "beta").put(Compound("beta", Some(3)))
    * ...     _ <- compoundTable.given(Condition('a -> "gamma") and attributeExists('maybe)).put(Compound("gamma", Some(42)))
    * ...     compounds <- compoundTable.scan()
    * ...   } yield compounds
    * ...   Scanamo.exec(client)(ops).toList
    * ... }
    * List(Right(Compound(beta,Some(3))), Right(Compound(alpha,None)), Right(Compound(gamma,None)))
    * }}}
    *
    * or with `or`
    *
    * {{{
    * >>> case class Choice(number: Int, description: String)
    * >>> val choicesTable = Table[Choice]("choices")
    * >>> LocalDynamoDB.withTable(client)("choices")('number -> N) {
    * ...   val ops = for {
    * ...     _ <- choicesTable.putAll(List(Choice(1, "cake"), Choice(2, "crumble"), Choice(3, "custard")))
    * ...     _ <- choicesTable.given(Condition('description -> "cake") or 'description -> "death").put(Choice(1, "victoria sponge"))
    * ...     _ <- choicesTable.given(Condition('description -> "cake") or 'description -> "death").put(Choice(2, "victoria sponge"))
    * ...     choices <- choicesTable.scan()
    * ...   } yield choices
    * ...   Scanamo.exec(client)(ops).toList
    * ... }
    * List(Right(Choice(2,crumble)), Right(Choice(1,victoria sponge)), Right(Choice(3,custard)))
    * }}}
    *
    * The same forms of condition can be applied to deletions
    *
    * {{{
    * >>> case class Gremlin(number: Int, wet: Boolean)
    * >>> val gremlinsTable = Table[Gremlin]("gremlins")
    * >>> LocalDynamoDB.withTable(client)("gremlins")('number -> N) {
    * ...   val ops = for {
    * ...     _ <- gremlinsTable.putAll(List(Gremlin(1, false), Gremlin(2, true)))
    * ...     _ <- gremlinsTable.given('wet -> true).delete('number -> 1)
    * ...     _ <- gremlinsTable.given('wet -> true).delete('number -> 2)
    * ...     remainingGremlins <- gremlinsTable.scan()
    * ...   } yield remainingGremlins
    * ...   Scanamo.exec(client)(ops).toList
    * ... }
    * List(Right(Gremlin(1,false)))
    * }}}
    */
  def given[T: ConditionExpression](condition: T) = ScanamoFree.given(name)(condition)
}

private[scanamo] case class Index[V: DynamoFormat](tableName: String, indexName: String) {
  /**
    * Query or scan an index, limiting the number of items evaluated by Dynamo
    * {{{
    * >>> case class Transport(mode: String, line: String, colour: String)
    * >>> val transport = Table[Transport]("transport")
    *
    * >>> val client = LocalDynamoDB.client()
    * >>> import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType._
    * >>> import com.gu.scanamo.syntax._
    *
    * >>> LocalDynamoDB.withTableWithSecondaryIndex(client)("transport", "colour-index")(
    * ...   'mode -> S, 'line -> S)('mode -> S, 'colour -> S
    * ... ) {
    * ...   val operations = for {
    * ...     _ <- transport.putAll(List(
    * ...       Transport("Underground", "Circle", "Yellow"),
    * ...       Transport("Underground", "Metropolitan", "Magenta"),
    * ...       Transport("Underground", "Central", "Red"),
    * ...       Transport("Underground", "Picadilly", "Blue"),
    * ...       Transport("Underground", "Northern", "Black")))
    * ...     somethingBeginningWithBl <- transport.index("colour-index").limit(1).query(
    * ...       ('mode -> "Underground" and ('colour beginsWith "Bl")).descending
    * ...     )
    * ...   } yield somethingBeginningWithBl.toList
    * ...   Scanamo.exec(client)(operations)
    * ... }
    * List(Right(Transport(Underground,Picadilly,Blue)))
    * }}}
    */
  def limit(n: Int) = IndexLimit(this, n)
}

private[scanamo] case class TableLimit[V: DynamoFormat](table: Table[V], limit: Int)
private[scanamo] case class IndexLimit[V: DynamoFormat](index: Index[V], limit: Int)

/* typeclass */trait Scannable[T[_], V] {
  def scan(t: T[V])(): ScanamoOps[List[Xor[DynamoReadError, V]]]
}

object Scannable {
  def apply[T[_], V](implicit s: Scannable[T, V]) = s

  trait Ops[T[_], V] {
    val instance: Scannable[T, V]
    def self: T[V]
    def scan() = instance.scan(self)()
  }

  trait ToScannableOps {
    implicit def scannableOps[T[_], V](t: T[V])(implicit s: Scannable[T, V]) = new Ops[T, V] {
      val instance = s
      val self = t
    }
  }

  implicit def tableScannable[V: DynamoFormat] = new Scannable[Table, V] {
    override def scan(t: Table[V])(): ScanamoOps[List[Xor[DynamoReadError, V]]] =
      ScanamoFree.scan[V](t.name)
  }
  implicit def indexScannable[V: DynamoFormat] = new Scannable[Index, V] {
    override def scan(i: Index[V])(): ScanamoOps[List[Xor[DynamoReadError, V]]] =
      ScanamoFree.scanIndex[V](i.tableName, i.indexName)
  }

  implicit def limitedTableScannable[V: DynamoFormat] = new Scannable[TableLimit, V] {
    override def scan(t: TableLimit[V])(): ScanamoOps[List[Xor[DynamoReadError, V]]] =
      ScanamoFree.scanWithLimit[V](t.table.name, t.limit)
  }
  implicit def limitedIndexScannable[V: DynamoFormat] = new Scannable[IndexLimit, V] {
    override def scan(i: IndexLimit[V])(): ScanamoOps[List[Xor[DynamoReadError, V]]] =
      ScanamoFree.scanIndexWithLimit[V](i.index.tableName, i.index.indexName, i.limit)
  }
}

/* typeclass */ trait Queryable[T[_], V] {
  def query(t: T[V])(query: Query[_]): ScanamoOps[List[Xor[DynamoReadError, V]]]
}

object Queryable {
  def apply[T[_], V](implicit s: Queryable[T, V]) = s

  trait Ops[T[_], V] {
    val instance: Queryable[T, V]
    def self: T[V]
    def query(query: Query[_]) = instance.query(self)(query)
  }

  trait ToQueryableOps {
    implicit def queryableOps[T[_], V](t: T[V])(implicit s: Queryable[T, V]) = new Ops[T, V] {
      val instance = s
      val self = t
    }
  }

  implicit def tableQueryable[V: DynamoFormat] = new Queryable[Table, V] {
    override def query(t: Table[V])(query: Query[_]): ScanamoOps[List[Xor[DynamoReadError, V]]] =
      ScanamoFree.query[V](t.name)(query)
  }
  implicit def indexQueryable[V: DynamoFormat] = new Queryable[Index, V] {
    override def query(i: Index[V])(query: Query[_]): ScanamoOps[List[Xor[DynamoReadError, V]]] =
      ScanamoFree.queryIndex[V](i.tableName, i.indexName)(query)
  }
  implicit def limitedTableQueryable[V: DynamoFormat] = new Queryable[TableLimit, V] {
    override def query(t: TableLimit[V])(query: Query[_]): ScanamoOps[List[Xor[DynamoReadError, V]]] =
      ScanamoFree.queryWithLimit[V](t.table.name)(query, t.limit)
  }
  implicit def limitedIndexQueryable[V: DynamoFormat] = new Queryable[IndexLimit, V] {
    override def query(i: IndexLimit[V])(query: Query[_]): ScanamoOps[List[Xor[DynamoReadError, V]]] =
      ScanamoFree.queryIndexWithLimit[V](i.index.tableName, i.index.indexName)(query, i.limit)
  }
}
