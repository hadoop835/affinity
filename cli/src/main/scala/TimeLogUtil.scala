import com.indvd00m.ascii.render.{Point, Region, Render}
import com.indvd00m.ascii.render.api.{ICanvas, IContextBuilder, IRender}
import com.indvd00m.ascii.render.elements.{Dot, Label, Line, Rectangle}
import com.indvd00m.ascii.render.elements.plot.{Axis, AxisLabels}
import com.typesafe.config.ConfigFactory
import io.amient.affinity.core.storage.{LogStorage, LogStorageConf}
import io.amient.affinity.core.util.{EventTime, TimeRange}
import io.amient.affinity.kafka.KafkaLogStorage
import io.amient.affinity.kafka.KafkaStorage.KafkaStorageConf

import scala.collection.JavaConverters._

object TimeLogUtil {

  def apply(args: List[String]): Unit = args match {
    case bootstrap :: topic :: partition :: fuzz :: Nil => apply(bootstrap, topic, partition.toInt, fuzz.toInt)
    case bootstrap :: topic :: partition :: Nil => apply(bootstrap, topic, partition.toInt)
    case bootstrap :: topic :: Nil => apply(bootstrap, topic)
    case _ => printHelp()
  }

  def printHelp(): Unit = {
    "Usage: timelog <kafka-bootstarp> <topic> [<partition> [<fuzz-minutes>]]"
  }

  def apply(bootstrap: String, topic: String): Unit = {
    println("Available partitions: 0 - " + (getKafkaLog(bootstrap, topic).getNumPartitions-1))
  }

  def apply(bootstrap: String, topic: String, partition: Int, fuzzMinutes: Int = 5 ): Unit = {
    val log = getKafkaLog(bootstrap, topic)
    val range = TimeRange.UNBOUNDED
    println(s"calculating compaction stats for range: $range..\n")
    log.reset(partition, range)
    var minTimestamp = Long.MaxValue
    var maxTimestamp = Long.MinValue
    var maxPosition = Long.MinValue
    var minPosition = Long.MaxValue
    var numRecords = 0L

    var blockmints = Long.MaxValue
    var blockmaxts = Long.MinValue
    var startpos = -1L
    var endpos = -1L
    var lastts = -1L
    val blocks = List.newBuilder[(TimeRange, Long, Long)]
    def addblock(): Unit = {
      val timerange: TimeRange = new TimeRange(blockmints, blockmaxts)
      blocks += ((timerange, startpos, endpos))
      println(s"Block $startpos : $endpos -> $timerange")
      startpos = -1L
      endpos = -1L
      blockmaxts = Long.MinValue
      blockmints = Long.MaxValue
      lastts = -1L
    }
    log.boundedIterator.asScala.foreach {
      entry =>
        if (startpos > -1 && entry.position > endpos + 1) addblock()
        if (lastts > -1 && entry.timestamp < lastts - fuzzMinutes * 60000) addblock()
        if (lastts > -1 && entry.timestamp > lastts + fuzzMinutes * 60000) addblock()
        if (startpos == -1) startpos = entry.position
        minPosition = math.min(minPosition, entry.position)
        maxPosition = math.max(maxPosition, entry.position)
        endpos = entry.position
        lastts = entry.timestamp
        entry.position
        blockmints = math.min(blockmints, entry.timestamp)
        blockmaxts = math.max(blockmaxts, entry.timestamp)
        minTimestamp = math.min(minTimestamp, entry.timestamp)
        maxTimestamp = math.max(maxTimestamp, entry.timestamp)
        numRecords += 1
    }
    if (startpos > -1) addblock()
    println("number of records: " + numRecords)
    println("minimum timestamp: " + pretty(minTimestamp))
    println("maximum timestamp: " + pretty(maxTimestamp))
    println("minimum offset: " + minPosition)
    println("maximum offset: " + maxPosition)

    val render: IRender = new Render
    val builder: IContextBuilder = render.newBuilder
    val width = 180
    val height = 41
    val footer = 0
    builder.width(width).height(height)
//    builder.element(new Label("|" + pretty(minTimestamp).toString, 0, height-2))
//    builder.element(new Label(pretty(maxTimestamp).toString+"|", width - 21, height-2))
    val xratio = width.toDouble / (maxTimestamp - minTimestamp)
    val yratio = (height-footer).toDouble / (maxPosition - minPosition)
    blocks.result().foreach {
      case (timerange, startpos, endpos) =>
        val x = ((timerange.start - minTimestamp) * xratio).toInt
        val y = height - footer - ((endpos - minPosition) * yratio).toInt
        val w = math.max(0, ((timerange.end - timerange.start) * xratio).toInt)
        val h = math.max(0, ((endpos - startpos) * yratio).toInt)
        if (w < 2 || h < 2) {
          builder.element(new Line(new Point(x,y), new Point(x + w,y + h)))
        } else {
          builder.element(new Rectangle(x, y, w, h))
          if (w > 20) {
            builder.element(new Label(pretty(timerange.end).toString, x + w - 20, y + 1))
            if (h > 3) {
              builder.element(new Label(endpos.toString.reverse.padTo(19, ' ').reverse, x + w - 20, y + 2))
            }
            if (w > 42) {
              builder.element(new Label(startpos.toString, x + 1, y + h - 3))
              if (h > 3) {
                builder.element(new Label(pretty(timerange.start).toString, x + 1, y + h - 2))
              }
            } else if (h > 3) {
              builder.element(new Label(startpos.toString, x + 1, y + h - 2))
            }
          }

        }
    }

    val canvas: ICanvas = render.render(builder.build)
    println(canvas.getText)
  }

  private def getKafkaLog(bootstrap: String, topic: String): KafkaLogStorage = {
    println(s"initializing $bootstrap / $topic")
    val conf = new LogStorageConf().apply(ConfigFactory.parseMap(Map(
      LogStorage.StorageConf.Class.path -> classOf[KafkaLogStorage].getName(),
      KafkaStorageConf.BootstrapServers.path -> bootstrap,
      KafkaStorageConf.Topic.path -> topic
    ).asJava))
//    conf.Class.setValue(classOf[KafkaLogStorage])
//    KafkaStorageConf(conf).BootstrapServers.setValue(bootstrap)
//    KafkaStorageConf(conf).Topic.setValue(topic)
    LogStorage.newInstance(conf).asInstanceOf[KafkaLogStorage]
  }

  private def pretty(unix: Long): String = {
    EventTime.local(unix).toString.replace("Z", "").replace("T", " ")
  }
}