package com.foursquare.twofishes

import com.foursquare.twofishes.util.GeometryUtils

import java.io._
import java.net.URI
import java.nio.ByteBuffer
import java.util.Arrays

import org.apache.hadoop.conf.Configuration 
import org.apache.hadoop.fs.{LocalFileSystem, Path}
import org.apache.hadoop.hbase.KeyValue.KeyComparator
import org.apache.hadoop.hbase.io.hfile.{CacheConfig, Compression, HFile, HFileScanner}
import org.apache.hadoop.hbase.util.Bytes._

import com.twitter.util.{Duration, Future, FuturePool}

import org.apache.thrift.{TDeserializer}
import org.apache.thrift.protocol.TCompactProtocol

import org.bson.types.ObjectId

import scala.collection.JavaConversions._
import scala.collection.mutable.HashMap
import scalaj.collection.Implicits._

class HFileStorageService(basepath: String) extends GeocodeStorageReadService {
  val nameMap = new NameIndexHFileInput(basepath)
  val oidMap = new GeocodeRecordHFileInput(basepath)
  val geomMap = new GeometryHFileInput(basepath)
  val s2mapOpt = ReverseGeocodeHFileInput.readInput(basepath)
  // will only be hit if we get a reverse geocode query
  lazy val s2map = s2mapOpt.getOrElse(
    throw new Exception("s2/revgeo index not built, please build s2_index.hfile"))

  val slugFidMapFuture = FuturePool.defaultPool {
    val (rv, duration) = Duration.inMilliseconds(readSlugMap())
    println("took %s seconds to read id map".format(duration.inSeconds))
    rv
  }

  lazy val slugFidMap = slugFidMapFuture.get()

  def readSlugMap() = {
    scala.io.Source.fromFile(new File(basepath, "id-mapping.txt")).getLines.map(l => {
      val parts = l.split("\t")
      (parts(0), new ObjectId(parts(1)))
    }).toMap
  }

  def getIdsByNamePrefix(name: String): Seq[ObjectId] = {
    nameMap.getPrefix(name)
  }

  def getIdsByName(name: String): Seq[ObjectId] = {
    nameMap.get(name)
  }

  def getByName(name: String): Seq[GeocodeServingFeature] = {
    nameMap.get(name).flatMap(oid => {
      oidMap.get(oid)
    })
  }

  def getByObjectIds(oids: Seq[ObjectId]): Map[ObjectId, GeocodeServingFeature] = {
    oids.flatMap(oid => oidMap.get(oid).map(r => (oid -> r))).toMap
  }

  def getBySlugOrFeatureIds(ids: Seq[String]) = {
    val oidMap = ids.flatMap(id => slugFidMap.get(id).map(oid => (oid, id))).toMap
    getByObjectIds(oidMap.keys.toList).map({
      case (k, v) => (oidMap(k), v)
    })
  }

  def getByS2CellId(id: Long): Seq[CellGeometry] = {
    s2map.get(id)
  }

  def getPolygonByObjectId(id: ObjectId): Option[Array[Byte]] = {
    geomMap.get(id)
  }

  def getMinS2Level: Int = s2map.minS2Level
  def getMaxS2Level: Int = s2map.maxS2Level
}

abstract class HFileInput(basepath: String, filename: String) {
  val conf = new Configuration()
  val fs = new LocalFileSystem()
  fs.initialize(URI.create("file:///"), conf)

  val path = new Path(new File(basepath, filename).getAbsolutePath())
  val cacheConfig = new CacheConfig(conf)
  println(cacheConfig)
  val reader = HFile.createReader(fs, path, cacheConfig)
  val fileInfo = reader.loadFileInfo().asScala

  def lookup(key: ByteBuffer): Option[ByteBuffer] = {
    val scanner: HFileScanner = reader.getScanner(true, true)
    if (scanner.reseekTo(key.array, key.position, key.remaining) == 0) {
      Some(scanner.getValue.duplicate())
    } else {
      None
    }
  }

  import scala.collection.mutable.ListBuffer
  
  def lookupPrefix(key: String, minPrefixRatio: Double = 0.5): Seq[Array[Byte]] = {
    val scanner: HFileScanner = reader.getScanner(true, true)
    scanner.seekTo(key.getBytes())
    if (!scanner.getKeyValue().getKeyString().startsWith(key)) {
      scanner.next()
    }


    val ret: ListBuffer[Array[Byte]] = new ListBuffer()

    // I hate to encode this logic here, but I don't really want to thread it
    // all the way through the storage logic.
    while (scanner.getKeyValue().getKeyString().startsWith(key)) {
      if ((key.size >= 3) ||
          (key.size*1.0 / scanner.getKeyValue().getKeyString().size) >= minPrefixRatio) {
        ret.append(scanner.getKeyValue().getValue())
      }
      scanner.next()
    }

    ret
  }

  def deserializeBytes[T <: org.apache.thrift.TBase[_ <: org.apache.thrift.TBase[_, _], _ <: org.apache.thrift.TFieldIdEnum]](struct: T, bytes: Array[Byte]): T = {
    val deserializer = new TDeserializer(new TCompactProtocol.Factory());
    deserializer.deserialize(struct, bytes);
    struct
  }
}

trait ObjectIdReader {
  def decodeObjectIds(bytes: Array[Byte]): Seq[ObjectId] = {
    0.until(bytes.length / 12).map(i => {
      new ObjectId(Arrays.copyOfRange(bytes, i * 12, (i + 1) * 12))
    })
  }
}

class NameIndexHFileInput(basepath: String) extends HFileInput(basepath, "name_index.hfile") with ObjectIdReader {
  val prefixMapOpt = PrefixIndexHFileInput.readInput(basepath)

  def get(name: String): List[ObjectId] = {
    val buf = ByteBuffer.wrap(name.getBytes())
    lookup(buf).toList.flatMap(b => {
      val bytes = new Array[Byte](b.capacity())
      b.get(bytes, 0, bytes.length);
      decodeObjectIds(bytes)
    })
  }

  def getPrefix(name: String): Seq[ObjectId] = {
    prefixMapOpt match {
      case Some(prefixMap) if (name.length <= prefixMap.maxPrefixLength) => {
        prefixMap.get(name)
      }
      case _  => {
        lookupPrefix(name).flatMap(bytes => {
          decodeObjectIds(bytes)
        })
      }
    }
  }
}

object PrefixIndexHFileInput {
  def readInput(basepath: String) = {
    if (new File(basepath, "prefix_index.hfile").exists()) {
      Some( new PrefixIndexHFileInput(basepath))
    } else {
      None
    }
  } 
}

class PrefixIndexHFileInput(basepath: String) extends HFileInput(basepath, "prefix_index.hfile") with ObjectIdReader {
  val maxPrefixLength = 5 // TODO: pull from hfile metadata  

  def get(name: String): List[ObjectId] = {
    val buf = ByteBuffer.wrap(name.getBytes())
    lookup(buf).toList.flatMap(b => {
      val bytes = new Array[Byte](b.capacity())
      b.get(bytes, 0, bytes.length);
      decodeObjectIds(bytes)
    })
  }
}

object ReverseGeocodeHFileInput {
  def readInput(basepath: String) = {
    if (new File(basepath, "s2_index.hfile").exists()) {
      Some( new ReverseGeocodeHFileInput(basepath))
    } else {
      None
    }
  } 
}

class ReverseGeocodeHFileInput(basepath: String) extends HFileInput(basepath, "s2_index.hfile") with ObjectIdReader {
  def fromByteArray(bytes: Array[Byte]) = {
    ByteBuffer.wrap(bytes).getInt()
  } 

  lazy val minS2Level = fromByteArray(fileInfo.getOrElse(
    "minS2Level".getBytes("UTF-8"),
    throw new Exception("missing minS2Level")))

  lazy val maxS2Level = fromByteArray(fileInfo.getOrElse(
    "maxS2Level".getBytes("UTF-8"),
    throw new Exception("missing maxS2Level")))

  def get(cellid: Long): List[CellGeometry] = {
    val buf = ByteBuffer.wrap(GeometryUtils.getBytes(cellid))
    lookup(buf).toList.flatMap(b => {
      val bytes = new Array[Byte](b.capacity())
      b.get(bytes, 0, bytes.length)
      val geometries = new CellGeometries()
      deserializeBytes(geometries, bytes)
      geometries.cells
    })
  }
}

class GeometryHFileInput(basepath: String) extends HFileInput(basepath, "geometry.hfile") {  
  def get(oid: ObjectId): Option[Array[Byte]] = {
    val buf = ByteBuffer.wrap(oid.toByteArray())
    lookup(buf).map(b => {
      val bytes = new Array[Byte](b.capacity())
      b.get(bytes, 0, bytes.length);
      bytes
    })
  }
}

class GeocodeRecordHFileInput(basepath: String) extends HFileInput(basepath, "features.hfile") {
  def get(oid: ObjectId): Option[GeocodeServingFeature] = {
    val buf = ByteBuffer.wrap(oid.toByteArray())
    lookup(buf).map(b => {
      val bytes = new Array[Byte](b.capacity())
      b.get(bytes, 0, bytes.length);
      deserializeBytes(new GeocodeServingFeature(), bytes)
    })
  }
}
