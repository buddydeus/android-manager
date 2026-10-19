package com.buddydeus.androidmanager.data

import com.buddydeus.androidmanager.domain.*
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.UUID

class AuditRepository(private val directory: File) : AuditSink {
    @Volatile var lastReadWarning:String=""
        private set
    private val format=DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)
    @Synchronized override fun append(event: AuditEvent) {
        check(directory.isDirectory || directory.mkdirs())
        val time=event.at.atZone(ScheduleEvaluator.zone)
        val line=JSONObject().put("eventId",UUID.randomUUID().toString()).put("occurredAt",time.toOffsetDateTime().toString())
            .put("category",event.category.name).put("action",event.action).put("result",event.result.take(500))
            .put("subject",event.subject).put("revision",event.revision).toString()
        RandomAccessFile(File(directory,"${time.toLocalDate().format(format)}.log"),"rw").use { f ->
            val size=f.length()
            if(size>0) { f.seek(size-1);if(f.read()!=10) { f.seek(size);f.write(10) } }
            f.seek(f.length());f.write((line+"\n").toByteArray(Charsets.UTF_8));f.fd.sync()
        }
    }
    @Synchronized fun readPage(date: LocalDate, category: LogCategory?, offset: Int, limit: Int): List<String> {
        lastReadWarning=""
        require(offset>=0 && limit in 1..200)
        val file=File(directory,"${date.format(format)}.log");if(!file.exists()) return emptyList()
        return file.bufferedReader().useLines { lines -> lines.mapNotNull { line -> runCatching { JSONObject(line) }.onFailure { lastReadWarning="部分日志行损坏，已跳过；其余记录仍可查看" }.getOrNull() }
            .filter { category==null || it.optString("category")==category.name }.drop(offset).take(limit).map { j ->
                val label=runCatching { LogCategory.valueOf(j.getString("category")).label }.getOrDefault("未知")
                "${j.optString("occurredAt")}  $label\n${j.optString("action")} ${j.optString("subject")}\n${j.optString("result")}" }.toList() }
    }
    @Synchronized fun cleanup(today: LocalDate) {
        val cutoff=today.minusDays(90)
        directory.listFiles()?.forEach { f ->
            if(f.isFile && f.name.matches(Regex("[0-9]{8}\\.log"))) {
                val date=runCatching { LocalDate.parse(f.name.removeSuffix(".log"),format) }.getOrNull()
                if(date!=null && date<cutoff) check(f.delete()) { "过期日志删除失败" }
            }
        }
    }
}
