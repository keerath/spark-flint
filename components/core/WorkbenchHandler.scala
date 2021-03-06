import java.io.{FileInputStream, BufferedInputStream, IOException, ByteArrayOutputStream, PrintStream, FileDescriptor};
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import scala.util.parsing.json.JSON
import scala.collection.mutable
import org.apache.spark.sql.SQLContext
import org.apache.spark.rdd.DoubleRDDFunctions
import org.apache.spark.util.StatCounter

import org.eclipse.jetty.server._
import org.eclipse.jetty.server.handler._
import org.codehaus.jackson.map.{ObjectMapper,JsonSerializer,SerializerProvider}
import org.codehaus.jackson.map.module.SimpleModule
import org.codehaus.jackson.JsonGenerator
import java.lang.management.ManagementFactory

/**
 * Simple servlet like thing for jetty, which runs simple commands from the UI
 */
class WorkbenchHandler(sqlContext: SQLContext, basePath:String = "./static/") extends org.eclipse.jetty.server.handler.AbstractHandler {

  private val urlExtractor = """/([\w|-]+)\.(html|json|js|css|png)/*""".r

  private val objectMapper = new ObjectMapper()

  private val memoryMBean = ManagementFactory.getMemoryMXBean()

  private val module = new SimpleModule("CustomSerializer", objectMapper.version)

  module.addSerializer(classOf[StatCounter], new StatCountSerializer)
  objectMapper.registerModule(module)

  @throws(classOf[IOException])
  @throws(classOf[ServletException])
  override def handle(target :String, baseRequest: Request,  request : HttpServletRequest, response : HttpServletResponse)  =
  {
    try {
      urlExtractor findFirstIn target match {
        case Some(urlExtractor(name, extension)) => sendFile(name,extension, response, request)
        case None => sendFile("editor","html", response, request)
      }
      response.setStatus(HttpServletResponse.SC_OK);
    } catch {
      case illegal: IllegalArgumentException =>     response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
    }
    baseRequest.setHandled(true);
  }

  def sendFile(fileNameWithoutExt :String, extension: String, response : HttpServletResponse, request : HttpServletRequest) {
    extension match {
      case "html" => response.setContentType("text/html;charset=utf-8");
        sendStaticFile(fileNameWithoutExt, extension, response)
      case "json"  => response.setContentType("application/json;charset=utf-8");
        sendCommandResponse(request, response)

      case "js" => response.setContentType("application/x-javascript")
        sendStaticFile(fileNameWithoutExt, extension, response)
      case "css" => response.setContentType("text/css")
        sendStaticFile(fileNameWithoutExt, extension, response)
      case "png" => response.setContentType("image/png")
        sendStaticFile(fileNameWithoutExt, extension, response)
      case _ => throw new IllegalArgumentException("unknown extension "+extension)
    }
  }

  def sendStaticFile(fileNameWithoutExt :String, extension: String, response : HttpServletResponse) {
    val bis = new BufferedInputStream(new FileInputStream(basePath + fileNameWithoutExt+"."+extension))
    val buffer = Stream.continually(bis.read).takeWhile(-1 !=).map(_.toByte).toArray
    bis.close();
    response.getOutputStream().write(buffer, 0, buffer.length);
  }

  def sendCommandResponse(request : HttpServletRequest, response : HttpServletResponse) {
    val payloadStr = request.getParameter("payload")
    val parsedPayload = JSON.parseFull(payloadStr).get.asInstanceOf[Map[String,String]]
    try {
      val DynamicRequestMaker(payload) = parsedPayload
      payload.commandName match {
        case "query" => response.getWriter().println(runQuery(payload.commandArgs))
        case "histogram" => response.getWriter().println(analyze(payload.commandArgs))
        case "desc" => response.getWriter().println(describe(payload.commandArgs))
        case "graph" =>  response.getWriter().println(getDataSet(payload.commandArgs))
        case "memory" => response.getWriter().println(getMemoryValues())
        case "scala" => response.getWriter().println(runScalaCommand(payload.commandArgs))
        case _ => reportFailure(response, new IllegalArgumentException(s"payload command sent as $payload.commandName"))
      }
    } catch {
      case ex : Exception => reportFailure(response,ex)
    }
  }

  private def reportFailure(response : HttpServletResponse, ex:Exception) {
    ex.printStackTrace
    response.getWriter().println("""{"success":false}""")

  }

  private def runQuery(sql:String):String = {
    rowsToJSON(sqlContext.sql(sql).collect)
  }

  private def getDataSet(dataSetName:String):String = {
    toJSON(DataSetManager.get(dataSetName))
  }

  private def analyze(commandArgs:String):String = {
    val analyzeArgsRegexp = """(\w+),\s*(\w+)""".r
    val analyzeArgsRegexp(tableName, columnName) = commandArgs
    val distSql = s"select count(*) as colCount from $tableName group by $columnName"
    val doubleCounts = sqlContext.sql(distSql).collect.map {row=> row(0).asInstanceOf[Long].toDouble}
    val stater = new DoubleRDDFunctions(sc.parallelize(doubleCounts))

    return """{ "metadata" : {"tableName":"""" + tableName + """", "columnName":"""" + columnName +""""}, "stats":""" + toJSON(stater.stats) + ""","histogram":"""+ toJSON(stater.histogram(10)) +"}"
  }

  private def describe(tableName:String):String = {
    if (tableName == null || tableName.trim().equals("")) {
      def allow(field: java.lang.reflect.Field)=field.setAccessible(true)
      val catalogField = sqlContext.getClass.getDeclaredField("catalog");
      allow(catalogField)
      val catalog = catalogField.get(sqlContext).asInstanceOf[org.apache.spark.sql.catalyst.analysis.SimpleCatalog]
      val datasetNames:List[String] = DataSetManager.getAllNames()
      if (catalog != null) {
        val tablesField = catalog.getClass.getDeclaredField("tables")
        allow(tablesField)
        val tableNames = tablesField.get(catalog);
        if (tableNames != null){
          return toJSON((datasetNames ++ tableNames.asInstanceOf[mutable.HashMap[String, org.apache.spark.sql.catalyst.plans.logical.LogicalPlan]].keys.toList).toArray)
        }
      }
      return toJSON(datasetNames.toArray)
    } else {
      val columnSeq = sqlContext.sql(s"select * from $tableName limit 1").queryExecution.analyzed.output.map {attr => (attr.name, attr.dataType.toString)}
      return toJSON(columnSeq.toArray)
    }
    return "[]"
  }

  private def runScalaCommand(command:String):String = {
    val sparkILoop = org.apache.spark.repl.Main.interp
    try {
      val originalOut = scala.Console.out
      scala.Console.setOut(new DelegatingPrintStream(originalOut))
      CaptureAllBuffer.clear
      sparkILoop.command(command)
      val resultStr = CaptureAllBuffer.toString
      scala.Console.setOut(originalOut)
      return toJSON(resultStr)
    } catch {
      case ex: Exception => ex.getMessage()
    } 
  }

  private def getMemoryValues():String = {
    toJSON(memoryMBean.getHeapMemoryUsage())
  }

  private def rowsToJSON(rows: Array[org.apache.spark.sql.Row]):String = {
    toJSON(rows.map {_.toArray})
  }

  private def toJSON(value: Any):String ={
    objectMapper.writeValueAsString(value)
  }

  case class DynamicRequest(commandName:String, commandArgs:String)

  object DynamicRequestMaker{
    def unapply(values: Map[String,String]) = 
      try{
        Some(DynamicRequest(values.get("command").get, values.get("args").get))
      } catch {
        case ex: Exception => None
      }
  }


  class StatCountSerializer extends JsonSerializer[StatCounter] {
    override def serialize(stat:StatCounter, jgen:JsonGenerator,  provider:SerializerProvider) = {
      jgen.writeStartObject();
      jgen.writeNumberField("count", stat.count);
      jgen.writeNumberField("mean", stat.mean);
      jgen.writeNumberField("min", stat.min);
      jgen.writeNumberField("max", stat.max);
      jgen.writeNumberField("stdev", stat.stdev);
      jgen.writeEndObject();
    }
  }

}

