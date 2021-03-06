package com.foursquare.twofishes.output

import com.foursquare.twofishes.{Indexes, SlugEntryMap}
import com.foursquare.twofishes.util.StoredFeatureId
import com.foursquare.twofishes.Identity._
import com.mongodb.Bytes
import com.mongodb.casbah.Imports._
import com.novus.salat._
import com.novus.salat.annotations._
import com.novus.salat.dao._
import com.novus.salat.global._
import java.io._
import org.apache.hadoop.hbase.util.Bytes._
import scalaj.collection.Implicits._
import com.foursquare.twofishes.mongo.MongoGeocodeDAO

class IdIndexer(
  override val basepath: String,
  override val fidMap: FidMap,
  slugEntryMap: SlugEntryMap.SlugEntryMap
) extends Indexer {
  val index = Indexes.IdMappingIndex
  override val outputs = Seq(index)

  def writeIndexImpl() {
    val slugEntries: List[(String, StoredFeatureId)] = for {
      (slug, entry) <- slugEntryMap.toList
      fid <- StoredFeatureId.fromHumanReadableString(entry.id)
      canonicalFid <- fidMap.get(fid)
    } yield {
      slug -> canonicalFid
    }

    val featureCursor = MongoGeocodeDAO.find(MongoDBObject())
    featureCursor.option = Bytes.QUERYOPTION_NOTIMEOUT
    val extraIds: List[(String, StoredFeatureId)]  = featureCursor.flatMap(f => {
      f.ids.filterNot(_ =? f._id).flatMap(id => {
        for {
          extraId <- StoredFeatureId.fromLong(id)
        } yield { (extraId.humanReadableString -> f.featureId) }
      })
    }).toList

    val writer = buildMapFileWriter(index)

    val sortedEntries = (slugEntries ++ extraIds).distinct.sortWith((a, b) => lexicalSort(a._1, b._1)).foreach({case (k, v) => {
      writer.append(k, v)
    }})

    writer.close()
  }
}
