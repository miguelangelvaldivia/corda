Persistence
===========

Corda offers developers the option to expose all or some part of a contract state to an *Object Relational Mapping*
(ORM) tool to be persisted in a RDBMS.  The purpose of this is to assist *vault* development by effectively indexing
persisted contract states held in the vault for the purpose of running queries over them and to allow relational joins
between Corda data and private data local to the organisation owning a node.

The ORM mapping is specified using the `Java Persistence API <https://en.wikipedia.org/wiki/Java_Persistence_API>`_
(JPA) as annotations and is converted to database table rows by the node automatically every time a state is recorded
in the node's local vault as part of a transaction.

.. note:: Presently the node includes an instance of the H2 database but any database that supports JDBC is a
candidate and the node will in the future support a range of database implementations via their JDBC drivers.  Much
of the node internal state is also persisted there. You can access the internal H2 database via JDBC, please see the
info in ":doc:`node-administration`" for details.

Schemas
-------
Every ``ContractState`` can implement the ``QueryableState`` interface if it wishes to be inserted into the node's local
database and accessible using SQL.

.. literalinclude:: ../../core/src/main/kotlin/net/corda/core/schemas/PersistentTypes.kt
    :language: kotlin
    :start-after: DOCSTART QueryableState
    :end-before: DOCEND QueryableState

The ``QueryableState`` interface requires the state to enumerate the different relational schemas it supports, for
instance in cases where the schema has evolved, with each one being represented by a ``MappedSchema`` object return
by the ``supportedSchemas()`` method.  Once a schema is selected it must generate that representation when requested
via the ``generateMappedObject()`` method which is then passed to the ORM.

Nodes have an internal ``SchemaService`` which decides what to persist and what not by selecting the ``MappedSchema``
to use.

.. literalinclude:: ../../node/src/main/kotlin/net/corda/node/services/api/SchemaService.kt
    :language: kotlin
    :start-after: DOCSTART SchemaService
    :end-before: DOCEND SchemaService

.. literalinclude:: ../../core/src/main/kotlin/net/corda/core/schemas/PersistentTypes.kt
    :language: kotlin
    :start-after: DOCSTART MappedSchema
    :end-before: DOCEND MappedSchema

The ``SchemaService`` can be configured by a node administrator to select the schemas used by each app. In this way the
relational view of ledger states can evolve in a controlled fashion in lock-step with internal systems or other
integration points and not necessarily with every upgrade to the contract code. It can select from the
``MappedSchema`` offered by a ``QueryableState``, automatically upgrade to a later version of a schema or even
provide a ``MappedSchema`` not originally offered by the ``QueryableState``.

It is expected that multiple different contract state implementations might provide mappings to some common schema.
For example an Interest Rate Swap contract and an Equity OTC Option contract might both provide a mapping to a common
Derivative schema. The schemas should typically not be part of the contract itself and should exist independently of it
to encourage re-use of a common set within a particular business area or Cordapp.

``MappedSchema`` offer a family name that is disambiguated using Java package style name-spacing derived from the
class name of a *schema family* class that is constant across versions, allowing the ``SchemaService`` to select a
preferred version of a schema.

The ``SchemaService`` is also responsible for the ``SchemaOptions`` that can be configured for a particular
``MappedSchema`` which allow the configuration of a database schema or table name prefixes to avoid any clash with
other ``MappedSchema``.

.. note:: It is intended that there should be plugin support for the ``SchemaService`` to offer the version upgrading
   and additional schemas as part of Cordapps, and that the active schemas be configurable.  However the present
   implementation offers none of this and simply results in all versions of all schemas supported by a
   ``QueryableState`` being persisted. This will change in due course. Similarly, it does not currently support
   configuring ``SchemaOptions`` but will do so in the future.

Object relational mapping
-------------------------
The persisted representation of a ``QueryableState`` should be an instance of a ``PersistentState`` subclass,
constructed either by the state itself or a plugin to the ``SchemaService``.  This allows the ORM layer to always
associate a ``StateRef`` with a persisted representation of a ``ContractState`` and allows joining with the set of
unconsumed states in the vault.

The ``PersistentState`` subclass should be marked up as a JPA 2.1 *Entity* with a defined table name and having
properties (in Kotlin, getters/setters in Java) annotated to map to the appropriate columns and SQL types.  Additional
entities can be included to model these properties where they are more complex, for example collections, so the mapping
does not have to be *flat*. The ``MappedSchema`` must provide a list of all of the JPA entity classes for that schema
in order to initialise the ORM layer.

Several examples of entities and mappings are provided in the codebase, including ``Cash.State`` and
``CommercialPaper.State``. For example, here's the first version of the cash schema.

.. literalinclude:: ../../finance/src/main/kotlin/net/corda/schemas/CashSchemaV1.kt
    :language: kotlin