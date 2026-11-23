package com.yap.app.data.remote

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * 测试用的假服务端：`ServerSocket` + 手写 HTTP/1.1，**不引 MockWebServer**。
 *
 * 为什么不用 OkHttp `Interceptor` 冒充：SSE 的「分片到达」与「取消协程要关连接」这两条行为
 * 必须在**真实 socket** 上验——假 body 里没有连接，`Call.cancel()` 关不掉任何东西，
 * 测试就变成了自欺欺人。项目已有 OkHttp 但没有 `mockwebserver` 依赖，几十行 ServerSocket 够用。
 *
 * 支持的形状：
 * - [FakeReply.Json]：带 `Content-Length` 的普通响应（任意状态码，用来验错误体映射）；
 * - [FakeReply.Stream]：无长度、`Connection: close` 的流式响应，writer 自己控制分片与节奏
 *   （验 SSE 多行/分片/取消/超时）。
 *
 * 一次只处理一个连接：所有测试都是单请求串行（分页/状态各一条），
 * 需要并发时再扩，不在 P1a 的范围里。
 */
internal class FakeHttpTestServer : Closeable {

    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val clients = CopyOnWriteArrayList<Socket>()

    val baseUrl: String = "http://127.0.0.1:${serverSocket.localPort}"

    val requests = CopyOnWriteArrayList<RecordedRequest>()

    @Volatile
    var responder: (RecordedRequest) -> FakeReply = { FakeReply.Json("{}") }

    private val acceptThread = thread(isDaemon = true, name = "fake-http-server") {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (closed: IOException) {
                return@thread
            }
            clients += socket
            handle(socket)
            clients -= socket
        }
    }

    /** 便捷：固定回一个 JSON 响应。 */
    fun respondJson(body: String, status: Int = 200) {
        responder = { FakeReply.Json(body, status) }
    }

    /** 便捷：延迟 [delayMillis] 再回（用来验客户端的读超时；假服务端是单线程的，够用）。 */
    fun respondAfter(delayMillis: Long, body: String, status: Int = 200) {
        responder = {
            Thread.sleep(delayMillis)
            FakeReply.Json(body, status)
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            val request = try {
                readRequest(client.getInputStream())
            } catch (broken: IOException) {
                return
            } ?: return
            requests += request
            val reply = responder(request)
            try {
                when (reply) {
                    is FakeReply.Json -> writeJson(client.getOutputStream(), reply)
                    is FakeReply.Stream -> {
                        writeStreamHeader(client.getOutputStream(), reply.contentType)
                        reply.writer(FakeExchange(request, client))
                    }
                }
            } catch (broken: IOException) {
                // 客户端提前断开（取消 / 读超时 / 响应体上限）是预期行为，不是测试失败。
            }
        }
    }

    override fun close() {
        runCatching { serverSocket.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        acceptThread.join(1_000)
    }

    // ------------------------------------------------------------------ HTTP

    private fun readRequest(input: InputStream): RecordedRequest? {
        val header = ByteArrayOutputStream()
        var window = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            header.write(byte)
            window = (window shl 8) or (byte and 0xFF)
            if (window == 0x0D0A0D0A) break
            if (header.size() > MAX_HEADER_BYTES) return null
        }
        val lines = header.toString("UTF-8").split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val requestLine = lines.first().split(" ")
        if (requestLine.size < 2) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(body, read, length - read)
            if (count < 0) break
            read += count
        }
        return RecordedRequest(
            method = requestLine[0],
            target = requestLine[1],
            headers = headers,
            body = String(body, 0, read, Charsets.UTF_8)
        )
    }

    private fun writeJson(output: OutputStream, reply: FakeReply.Json) {
        val body = reply.body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(reply.status).append(' ').append(reason(reply.status)).append("\r\n")
            append("Content-Type: ").append(reply.contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun writeStreamHeader(output: OutputStream, contentType: String) {
        // 不写 Content-Length、不写 chunked：OkHttp 会读到连接关闭为止，正好是 SSE 的形状。
        val header = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        else -> "Status"
    }

    // ------------------------------------------------------------------ 数据

    internal data class RecordedRequest(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: String
    ) {
        val path: String get() = target.substringBefore('?')

        val query: Map<String, String>
            get() = target.substringAfter('?', "")
                .split('&')
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
                }

        private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
    }

    internal sealed class FakeReply {
        class Json(
            val body: String,
            val status: Int = 200,
            val contentType: String = "application/json; charset=utf-8"
        ) : FakeReply()

        class Stream(
            val contentType: String = "text/event-stream",
            // writer 放最后：测试里 `FakeReply.Stream { exchange -> … }` 靠尾随 lambda 更顺手。
            val writer: (FakeExchange) -> Unit
        ) : FakeReply()
    }

    /** 流式响应里给 writer 的出口：可以分片写，也可以阻塞读输入（用来观察客户端关连接）。 */
    internal class FakeExchange(val request: RecordedRequest, private val socket: Socket) {
        val output: OutputStream get() = socket.getOutputStream()
        val input: InputStream get() = socket.getInputStream()

        fun write(text: String) {
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        }

        fun write(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }
    }

    private companion object {
        const val MAX_HEADER_BYTES = 64 * 1024
    }
}

/** 轮询等待条件成立（测试里用「事件到了没有」这种异步信号，比 sleep 固定时长稳）。 */
internal fun awaitUntil(timeoutMillis: Long = 5_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(5)
    }
    return condition()
}
