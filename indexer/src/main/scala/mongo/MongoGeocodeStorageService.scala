// Copyright 2012 Foursquare Labs Inc. All Rights Reserved.
package com.foursquare.twofishes.mongo

import com.foursquare.twofishes.{GeocodeRecord, BoundingBox, DisplayName}
import com.foursquare.twofishes.util.StoredFeatureId
import com.mongodb.Bytes
import com.mongodb.casbah.Imports._
import com.mongodb.casbah.MongoConnection
import com.novus.salat._
import com.novus.salat.annotations._
import com.novus.salat.dao._
import com.novus.salat.global._

class MongoGeocodeStorageService extends GeocodeStorageWriteService {
  def getById(id: StoredFeatureId): Iterator[GeocodeRecord] = {
    val geocodeCursor = MongoGeocodeDAO.find(MongoDBObject("_id" -> id.longId))
    geocodeCursor.option = Bytes.QUERYOPTION_NOTIMEOUT
    geocodeCursor
  }

  def insert(record: GeocodeRecord) {
    MongoGeocodeDAO.insert(record)
  }

  def insert(records: List[GeocodeRecord]) {
    MongoGeocodeDAO.insert(records)
  }

  def addBoundingBoxToRecord(bbox: BoundingBox, id: StoredFeatureId) {
    MongoGeocodeDAO.update(MongoDBObject("_id" -> id.longId),
      MongoDBObject("$set" -> MongoDBObject("boundingbox" -> grater[BoundingBox].asDBObject(bbox))),
      false, false)
  }

  def addNameToRecord(name: DisplayName, id: StoredFeatureId) {
    MongoGeocodeDAO.update(MongoDBObject("_id" -> id.longId),
      MongoDBObject("$addToSet" -> MongoDBObject("displayNames" -> grater[DisplayName].asDBObject(name))),
      false, false)
  }

  def addNameIndex(name: NameIndex) {
    NameIndexDAO.insert(name)
  }

  def addNameIndexes(names: List[NameIndex]) {
    NameIndexDAO.insert(names)
  }

  def addPolygonToRecord(id: StoredFeatureId, polyId: ObjectId) {
    MongoGeocodeDAO.update(MongoDBObject("_id" -> id.longId),
      MongoDBObject("$set" ->
        MongoDBObject(
          "hasPoly" -> true,
          "polyId" -> polyId
        )
      ),
      false, false)
  }

  def addSlugToRecord(id: StoredFeatureId, slug: String) {
    MongoGeocodeDAO.update(MongoDBObject("_id" -> id.longId),
      MongoDBObject("$set" -> MongoDBObject("slug" -> slug)),
      false, false)
  }

  def setRecordNames(id: StoredFeatureId, names: List[DisplayName]) {
    MongoGeocodeDAO.update(MongoDBObject("_id" -> id.longId),
      MongoDBObject("$set" -> MongoDBObject(
        "displayNames" -> names.map(n => grater[DisplayName].asDBObject(n)))),
      false, false)
  }
}