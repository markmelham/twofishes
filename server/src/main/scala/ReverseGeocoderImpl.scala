//  Copyright 2012 Foursquare Labs Inc. All Rights Reserved
package com.foursquare.twofishes

import com.foursquare.twofishes.Identity._
import com.foursquare.twofishes.util.{GeometryUtils, StoredFeatureId}
import com.foursquare.twofishes.util.Lists.Implicits._
import com.twitter.ostrich.stats.Stats
import com.twitter.util.Duration
import com.vividsolutions.jts.geom.{Coordinate, Geometry, GeometryFactory, Point => JTSPoint}
import com.vividsolutions.jts.io.WKBReader
// import com.weiglewilczek.slf4s.Logging
import org.apache.thrift.TBaseHelper
import scala.collection.mutable.ListBuffer
import scalaj.collection.Implicits._

object ReverseGeocodeParseOrdering {
  def parseToSortKey(p: Parse[Sorted]) = {
    for {
      featureMatch <- p.headOption
    } yield {
      val servingFeature = featureMatch.fmatch
      val woeTypeInt = YahooWoeTypes.getOrdering(
        servingFeature.feature.woeTypeOption.getOrElse(YahooWoeType.UNKNOWN))
      val boost = servingFeature.scoringFeatures.boost
      val distance = p.scoringFeaturesOption.flatMap(s => Some(s.featureToRequestCenterDistance.toInt)).getOrElse(0)
      val coverage = p.scoringFeaturesOption.flatMap(s => Some(s.percentOfRequestCovered.toInt)).getOrElse(0)
      (woeTypeInt, distance, -1*boost, -1*coverage)
    }
  }

  val ParseOrdering: Ordering[Parse[Sorted]] = Ordering.by(parseToSortKey)
}

class ReverseGeocoderHelperImpl(
  store: GeocodeStorageReadService,
  req: CommonGeocodeRequestParams,
  queryLogger: MemoryLogger
) extends GeocoderImplTypes  with BulkImplHelpers {
  def featureGeometryIntersections(wkbGeometry: Array[Byte], otherGeom: Geometry) = {
    val wkbReader = new WKBReader()
    val geom = wkbReader.read(wkbGeometry)
    try {
      (geom, geom.intersects(otherGeom))
    } catch {
      case e: Exception =>
        Stats.addMetric("intersects_exception", 1)
        // println("failed to calculate intersection: %s".format(otherGeom), e)
        (geom, false)
    }
  }

  def computeIntersectionArea(
    featureGeometry: Geometry,
    requestGeometry: Geometry
  ): Double = {
    try {
      featureGeometry.intersection(requestGeometry).getArea()
    } catch {
      case e: Exception =>
        Stats.addMetric("intersection_exception", 1)
        // println("failed to calculate intersection: %s x %s".format(featureGeometry, requestGeometry), e)
        0.0
    }
  }

  def responseIncludes(include: ResponseIncludes): Boolean =
    GeocodeRequestUtils.responseIncludes(req, include)

  def s2CoverGeometry(geom: Geometry): Seq[Long] = {
    geom match {
      case p: JTSPoint =>
        val levels = getAllLevels()
        queryLogger.ifDebug("doing point revgeo on %s at levels %s", p, levels)
        levels.map(level =>
          GeometryUtils.getS2CellIdForLevel(p.getCoordinate.y, p.getCoordinate.x, level).id()
        )
      case g =>
        val cellids = queryLogger.logDuration("s2_cover_time", "s2_cover_time") {
          GeometryUtils.coverAtAllLevels(
            geom,
            store.getMinS2Level,
            store.getMaxS2Level,
            Some(store.getLevelMod)
          ).map(_.id())
        }
        Stats.addMetric("num_geom_cells", cellids.size)
        cellids
    }
  }

  def findMatches(
    otherGeom: Geometry,
    cellGeometries: Seq[CellGeometry]
  ): Seq[StoredFeatureId] = {
    if (req.debug > 0) {
      queryLogger.ifDebug("had %d candidates", cellGeometries.size)
      // queryLogger.ifDebug("s2 cells: %s", cellids)
    }

    val matches = new ListBuffer[StoredFeatureId]()

    for {
      cellGeometry <- cellGeometries
      if (req.woeRestrict.isEmpty || cellGeometry.woeTypeOption.exists(req.woeRestrict.has))
      fid <- StoredFeatureId.fromLong(cellGeometry.longIdOrThrow)
    } yield {
      if (!matches.has(fid)) {
        if (cellGeometry.full) {
          queryLogger.ifDebug("was full: %s", fid)
          matches.append(fid)
        } else {
          cellGeometry.wkbGeometryOption match {
            case Some(wkbGeometry) =>
              val (geom, intersects) = queryLogger.logDuration("intersectionCheck", "intersectionCheck") {
                featureGeometryIntersections(TBaseHelper.byteBufferToByteArray(wkbGeometry), otherGeom)
              }
              if (intersects) {
                matches.append(fid)
              }
            case None => queryLogger.ifDebug("not full and no geometry for: %s", fid)
          }
        }
      }
    }

    matches.toSeq
  }

  def doBulkReverseGeocode(otherGeoms: Seq[Geometry]):
      (Seq[Seq[Int]], Seq[GeocodeInterpretation], Seq[GeocodeFeature]) = {
    Stats.addMetric("revgeo.num-geoms", otherGeoms.size)
    val geomIndexToCellIdMap: Map[Int, Seq[Long]] = (for {
      (g, index) <- otherGeoms.zipWithIndex
    } yield { index -> s2CoverGeometry(g) }).toMap

    val cellGeometryMap: Map[Long, Seq[CellGeometry]] =
      (for {
        cellid: Long <- geomIndexToCellIdMap.values.flatten.toSet
      } yield {
        cellid -> store.getByS2CellId(cellid)
      }).toMap

    Stats.addMetric("revgeo.cells-to-lookup", cellGeometryMap.size)

    val geomToMatches = (for {
      (otherGeom, index) <- otherGeoms.zipWithIndex
    } yield {
      val cellGeometries = geomIndexToCellIdMap(index).flatMap(cellid => cellGeometryMap(cellid))

      val featureIds = findMatches(otherGeom, cellGeometries)

      (otherGeom, featureIds)
    })

    val matchedIds = geomToMatches.flatMap(_._2).toSet.toList
    Stats.addMetric("revgeo.candidates", matchedIds.size)

    // need to get polygons if we need to calculate coverage
    val polygonMap: Map[StoredFeatureId, Geometry] = (
      if (GeocodeRequestUtils.shouldFetchPolygon(req)) {
        Stats.incr("revgeo.polygons-fetched", 1)
        store.getPolygonByFeatureIds(matchedIds)
      } else {
        Map.empty
      }
    )

    val parseParams = ParseParams()
    val responseProcessor = new ResponseProcessor(req, store, queryLogger)
    val parsesAndOtherGeomToFids: Seq[(SortedParseSeq, (Geometry, Seq[StoredFeatureId]))] = (for {
      ((otherGeom, featureIds), index) <- geomToMatches.zipWithIndex
    } yield {
      val servingFeaturesMap: Map[StoredFeatureId, GeocodeServingFeature] =
        store.getByFeatureIds(featureIds.toSet.toList)

      // for each, check if we're really in it
      val otherGeomCentroid = otherGeom.getCentroid()
      val parses: SortedParseSeq = for {
	      fid <- featureIds
	      f <- servingFeaturesMap.get(fid)
      } yield {
        val parse = Parse[Sorted](List(FeatureMatch(0, 0, "", f)))
        val scoringFeatures = InterpretationScoringFeatures.newBuilder
        if (responseIncludes(ResponseIncludes.REVGEO_COVERAGE) &&
            otherGeom.getNumPoints > 2) {
          polygonMap.get(fid).foreach(geom => {
            if (geom.getNumPoints > 2) {
              Stats.time("revGeo.revgeo-coverage") {
                val overlapArea = computeIntersectionArea(geom, otherGeom)
                scoringFeatures.percentOfRequestCovered(100.0 * overlapArea / otherGeom.getArea())
                scoringFeatures.percentOfFeatureCovered(100.0 * overlapArea / geom.getArea())
              }
            }
            Stats.time("revGeo.point-to-shape-distance") {
              scoringFeatures.featureToRequestCenterDistance(
                // multiply distance in degrees by 100000 so it can be converted to Int
                100000.0 * GeometryUtils.pointToShapeDistanceInDegrees(otherGeomCentroid, geom))
            }
          })
        }
        parse.setScoringFeatures(Some(scoringFeatures.result))
        parse
      }

      val maxInterpretations = if (req.maxInterpretations <= 0) {
        parses.size
      } else {
        req.maxInterpretations
      }

      val sortedParses = parses.sorted(ReverseGeocodeParseOrdering.ParseOrdering).take(maxInterpretations)

      val filteredParses = responseProcessor.filterParses(sortedParses, parseParams)
      (filteredParses, (otherGeom -> filteredParses.flatMap(p => StoredFeatureId.fromLong(p.fmatches(0).fmatch.longId))))
    })
    val sortedParses = parsesAndOtherGeomToFids.flatMap(_._1)
    val otherGeomToFids = parsesAndOtherGeomToFids.map(_._2).toMap

    val interpretations = responseProcessor.hydrateParses(sortedParses, parseParams, polygonMap,
      fixAmbiguousNames = false)

    makeBulkReply[Geometry](otherGeoms, otherGeomToFids, interpretations)
  }

  def getAllLevels(): Seq[Int] = {
    for {
      level <- store.getMinS2Level.to(store.getMaxS2Level)
      if ((level - store.getMinS2Level) % store.getLevelMod) == 0
    } yield { level }
  }
}

class ReverseGeocoderImpl(
  store: GeocodeStorageReadService,
  req: GeocodeRequest
) extends GeocoderImplTypes {
  val queryLogger = new MemoryLogger(req)
  val commonParams = GeocodeRequestUtils.geocodeRequestToCommonRequestParams(req)
  val reverseGeocoder =
    new ReverseGeocoderHelperImpl(store, commonParams, queryLogger)

  def doSingleReverseGeocode(geom: Geometry): GeocodeResponse = {
    val (interpIdxes, interpretations, _) = reverseGeocoder.doBulkReverseGeocode(List(geom))
    val response = ResponseProcessor.generateResponse(req.debug, queryLogger,
      interpIdxes(0).flatMap(interpIdx => interpretations.lift(interpIdx)),
      requestGeom = if (req.debug > 0) { Some(geom) } else { None })
    response
  }


  def reverseGeocode(): GeocodeResponse = {
    Stats.incr("revgeo-requests", 1)

    if (req.radius > 50000) {
      //throw new Exception("radius too big (%d > %d)".format(req.radius, maxRadius))
      GeocodeResponse.newBuilder.interpretations(Nil).result
    }

    val geom = GeocodeRequestUtils.getRequestGeometry(req)
      .getOrElse(throw new Exception("no bounds or ll"))
    Stats.incr("revgeo-requests", 1)

    val statStr = if (geom.isInstanceOf[JTSPoint]) {
      "revgeo-point"
    } else {
      "revgeo-geom"
    }
    Stats.time(statStr) {
      doSingleReverseGeocode(geom)
    }
  }
}

class BulkReverseGeocoderImpl(
  store: GeocodeStorageReadService,
  req: BulkReverseGeocodeRequest
) extends GeocoderImplTypes {
  val params = req.paramsOption.getOrElse(CommonGeocodeRequestParams.newBuilder.result)

  val queryLogger = new MemoryLogger(params)
  val reverseGeocoder = new ReverseGeocoderHelperImpl(store, params, queryLogger)

  def reverseGeocode(): BulkReverseGeocodeResponse = {
    Stats.incr("bulk-revgeo-requests", 1)

    val geomFactory = new GeometryFactory()

    val points = req.latlngs.map(ll => geomFactory.createPoint(new Coordinate(ll.lng, ll.lat)))

    val (interpIdxs, interps, parents) = reverseGeocoder.doBulkReverseGeocode(points)

    val responseBuilder = BulkReverseGeocodeResponse.newBuilder
      .interpretationIndexes(interpIdxs)
      .interpretations(interps)
      .DEPRECATED_interpretationMap(Map.empty)
      .parentFeatures(parents)

    if (params.debug > 0) {
      responseBuilder.debugLines(queryLogger.getLines)
    }
    responseBuilder.result
  }
}

