package com.foursquare.twofishes.mongo

import com.foursquare.twofishes.{GeocodeRecord, DisplayName, BoundingBox}
import com.foursquare.twofishes.util.StoredFeatureId
import org.bson.types.ObjectId

trait GeocodeStorageWriteService {
  def insert(record: GeocodeRecord): Unit
  def insert(record: List[GeocodeRecord]): Unit
  def setRecordNames(id: StoredFeatureId, names: List[DisplayName])
  def addBoundingBoxToRecord(bbox: BoundingBox, id: StoredFeatureId)
  def addNameToRecord(name: DisplayName, id: StoredFeatureId)
  def addNameIndex(name: NameIndex)
  def addNameIndexes(names: List[NameIndex])
  def addPolygonToRecord(id: StoredFeatureId, polyId: ObjectId)
  def addSlugToRecord(id: StoredFeatureId, slug: String)
  def getById(id: StoredFeatureId): Iterator[GeocodeRecord]
}
